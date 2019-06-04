package org.csc.account.core.processor;

import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.protobuf.ByteString;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.ntrans.api.ActorService;
import onight.tfw.ntrans.api.annotation.ActorRequire;
import onight.tfw.outils.conf.PropHelper;
import onight.tfw.outils.pool.ReusefulLoopPool;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.csc.account.api.*;
import org.csc.account.bean.BlockStoreSummary;
import org.csc.account.bean.HashPair;
import org.csc.account.core.mis.MultiTransactionSeparator;
import org.csc.account.core.processor.TxRunnerInfo.IndexTransaction;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.BlockStateTrieRuntimeException;
import org.csc.account.gens.Blockimpl;
import org.csc.account.processor.BlockChainConfig;
import org.csc.account.tree64.DefaultStorage64;
import org.csc.account.tree64.EMTree64;
import org.csc.account.util.ByteUtil;
import org.csc.account.util.FastByteComparisons;
import org.csc.account.util.NodeDef;
import org.csc.bcapi.EncAPI;
import org.csc.bcapi.gens.Oentity.OKey;
import org.csc.bcapi.gens.Oentity.OPair;
import org.csc.bcapi.gens.Oentity.OValue;
import org.csc.evmapi.gens.Act;
import org.csc.evmapi.gens.Block;
import org.csc.evmapi.gens.Block.BlockBody;
import org.csc.evmapi.gens.Block.BlockTransactionResult;
import org.csc.evmapi.gens.Tx.Transaction;
import org.csc.evmapi.gens.Tx;
import org.csc.rcvm.utils.RLP;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 针对分片Block处理
 *
 * @author lance
 * @since 2019.1.9 18:15
 */
@Data
@Slf4j
@NActorProvider
@EqualsAndHashCode(callSuper = false)
@Instantiate(name = "V4_Processor")
@Provides(specifications = { ActorService.class })
public class V4Processor implements IProcessor, ActorService {
	/** 用来存放当前节点最大Block的StateRoot.Hash */
	private static byte[] rootHash;
	/**
	 * TX参与者互不相干
	 */
	private MultiTransactionSeparator mts = new MultiTransactionSeparator();
	/**
	 * 定义线程池大小
	 */
	private final static int POOL_SIZE = new PropHelper(null).get("org.csc.account.state.parallel",
			Runtime.getRuntime().availableProcessors() * 2);
	private static ExecutorService executor = new ForkJoinPool(POOL_SIZE);
	//private static ExecutorService executor = Executors.newFixedThreadPool(POOL_SIZE);

	@ActorRequire(name = "Account_Helper", scope = "global")
	private IAccountHelper accountHelper;
	@ActorRequire(name = "Transaction_Helper", scope = "global")
	private ITransactionHelper transactionHelper;
	@ActorRequire(name = "bc_encoder", scope = "global")
	private EncAPI encApi;
	@ActorRequire(name = "ETBlock_StateTrie", scope = "global")
	private IStateTrie stateTrie;
	@ActorRequire(name = "BlockChain_Helper", scope = "global")
	private IChainHelper blockChainHelper;
	/**
	 * 保存待打包block的交易
	 */
	@ActorRequire(name = "ConfirmTxHashDB", scope = "global")
	private IConfirmTxMap oConfirmMapDB;

	/**
	 * 账户不相干分离
	 *
	 * @param txs
	 *            交易
	 * @param block
	 *            当前Block
	 * @param accounts
	 *            账户集合
	 * @return 账户执行结果
	 * @throws Exception
	 *             Exception
	 */
	ReusefulLoopPool<IndexTransaction> indexPools = new ReusefulLoopPool<>();

	public IndexTransaction borrowTX(Tx.Transaction tx, List<BlockTransactionResult> results, int index) {
		IndexTransaction itx = indexPools.borrow();
		if (itx == null) {
			return new IndexTransaction(tx,results, index);
		}
		itx.tx = tx;
		itx.index = index;
		itx.results = results;
		return itx;
	}

	public void returnTX(IndexTransaction itx) {

		indexPools.retobj(itx);
	}

	private synchronized List<BlockTransactionResult> executeTx(Tx.Transaction[] txs, Block.BlockEntity block,
			Map<ByteString, Act.Account.Builder> accounts) throws Exception {
		List<Future<List<BlockTransactionResult>>> res = Lists.newArrayList();
		long tt_startup = System.currentTimeMillis();
		while(flushlock.get());
		long tt_getlock = System.currentTimeMillis();
		mts.reset();
		// 初始化
		CountDownLatch cdl = new CountDownLatch(txs.length);
		
		for (int i = 0; i < mts.bucketSize; i++) {
			TxRunnerInfo info = TxRunnerInfo.builder().accounts(accounts).block(block).cdl(cdl)
					.queue(mts.getTxnQueue(i)).transaction(transactionHelper).build();
			res.add(executor.submit(new MisV3TransactionRunner(info)));
		}
		List<BlockTransactionResult> results = new ArrayList<>();
		
		LinkedBlockingQueue<IndexTransaction> queue = new LinkedBlockingQueue<>();
		int index = 0;
		BlockTransactionResult ret = BlockTransactionResult.newBuilder().setI(BlockTransactionResult.I_FIELD_NUMBER).build();
		for (Tx.Transaction tx : txs) {
			results.add(ret);
			queue.add(borrowTX(tx,results, index));
			index++;
		}
		// trips
		if (indexPools.size() < 200000) {
			for (IndexTransaction itx : queue) {
				returnTX(itx);
			}
		}

		mts.doClearing(queue, transactionHelper, null, block.getHeader().getNumber(),cdl);
		for (int i = 0; i < mts.getSyncTxnQueue().size(); i++) {
			cdl.countDown();
		}
		cdl.await();
		//
		queue = mts.getSyncTxnQueue();
		int lastsize = queue.size();
		for (int i = 0; i < 5 && lastsize > 0; i++) {
			mts.reset();
			cdl = new CountDownLatch(lastsize);
			for (int ci = 0; ci < mts.bucketSize; ci++) {
				TxRunnerInfo info = TxRunnerInfo.builder().accounts(accounts).block(block).cdl(cdl)
						.queue(mts.getTxnQueue(ci)).transaction(transactionHelper).build();
				res.add(executor.submit(new MisV3TransactionRunner(info)));
			}
			mts.doClearing(queue, transactionHelper, null, block.getHeader().getNumber(),cdl);
			queue = mts.getSyncTxnQueue();
			for (int cc = 0; cc < queue.size(); cc++) {
				cdl.countDown();
			}
			cdl.await();
			if (lastsize - queue.size() < 100 || lastsize <= 100) {
				break;
			}
			lastsize = queue.size();
		}
		 int syncsize = mts.getSyncTxnQueue().size();

		// 等待分离器将所有交易分离完成并执行后，再执行无法分离的交易
		if (mts.getSyncTxnQueue().size() > 0) {
			TxRunnerInfo info = TxRunnerInfo.builder().accounts(accounts).block(block).queue(mts.getSyncTxnQueue())
					.transaction(transactionHelper).build();
			res.add(executor.submit(new MisV3SyncRunner(info, mts.getSyncTxnQueue().size())));
		}
		long tt_clearlock = System.currentTimeMillis();
		mts.outputLog();
		mts.resetBuffer();

		// 针对账户保存, 需要处理分片
		
//		List<BlockTransactionResult> results = res.parallelStream().flatMap(f -> {
//			try {
//				return f.get().stream();
//			} catch (Exception e) {
//				return null;
//			}
//		}).sorted(Comparator.comparing(BlockTransactionResult::getI)).collect(Collectors.toList());
		// 等待交易处理完成后, 保存账户
		long tt_results = System.currentTimeMillis();

		batchSaveAccount(accounts);
		// long flush_start = System.currentTimeMillis();
		long tt_saveAccount = System.currentTimeMillis();

		while(!flushlock.compareAndSet(false, true));
		long tt_waitLock = System.currentTimeMillis();
		new Thread(new Runnable() {
			@Override
			public void run() {
				// TODO Auto-generated method stub
				try {
					transactionHelper.flushDeferCache();
				}finally {
					flushlock.set(false);
				}
			}
		}).start();
		
		// long flush_end = System.currentTimeMillis();
		log.error("loadtest ==>exec.defermode=" + transactionHelper.isTxSaveDeferMode() + ",syncsize=" + syncsize + " cost=["
				+ "e="+(tt_getlock-tt_startup)+
				",c="+(tt_clearlock-tt_getlock)
				+",r="+(tt_results-tt_clearlock)
				+",s="+(tt_saveAccount-tt_results)
				+",w="+(tt_waitLock-tt_saveAccount)
				+",all="+(tt_waitLock - tt_startup) + "]");
		return results;
	}
	AtomicBoolean flushlock = new AtomicBoolean(false);
	/**
	 * 只保存本分片上面的账户信息
	 *
	 * @param accounts
	 *            Tx中涉及账户集合
	 */
	private void batchSaveAccount(Map<ByteString, Act.Account.Builder> accounts) {
		accountHelper.batchPutAccounts(accounts);
	}

	/**
	 * 执行Tx
	 *
	 * @param txs
	 *            tx集合
	 * @param curBlock
	 *            CurrentBlock
	 * @return 执行结果
	 * @throws BlockException
	 *             BlockException
	 */
	@Override
	public synchronized Map<ByteString, ByteString> executeTransaction(List<Tx.Transaction> txs,
			Block.BlockEntity curBlock) throws BlockException {
		Map<ByteString, ByteString> results = new HashMap<>(txs.size());

		Map<Integer, ITransactionActuator> actorByType = new HashMap<>();
		txs.forEach(tx -> {
			ITransactionActuator actuator = actorByType.get(tx.getBody().getType());
			if (actuator == null) {
				actuator = transactionHelper.getActuator(tx.getBody().getType(), curBlock);
				actorByType.put(tx.getBody().getType(), actuator);
			} else {
				transactionHelper.resetActuator(actuator, curBlock);
			}

			try {
				Map<ByteString, Act.Account.Builder> accounts = new HashMap<>();
				transactionHelper.merageTransactionAccounts(tx.toBuilder(), accounts);
				actuator.onPrepareExecute(tx, accounts);
				ByteString result = actuator.onExecute(tx, accounts);

				actuator.onExecuteDone(tx, curBlock, result);
				results.put(tx.getHash(), result);

				// 针对账户保存, 需要处理分片
				batchSaveAccount(accounts);
			} catch (Exception e) {
				log.error("block: {},exec tx hash: {}, error: ", convert(curBlock.getHeader().getHash()),
						convert(tx.getHash()), e);
				try {
					String errorMessage = e.getMessage() == null ? "unknown exception" : e.getMessage();
					actuator.onExecuteError(tx, curBlock, ByteString.copyFromUtf8(errorMessage));
					results.put(tx.getHash(), ByteString.copyFromUtf8(errorMessage));
				} catch (Exception ex) {
					log.error("onExec error: {}, ", ex.getMessage(), ex);
				}
			}
		});

		return results;
	}

	private String convert(ByteString hash) {
		return BlockChainConfig.convert(hash);
	}

	@Override
	public void applyReward(Block.BlockEntity.Builder curBlock) throws BlockException {
		byte[] reward = ByteUtil.bigIntegerToBytes(BlockChainConfig.minerReward.multiply(new BigInteger(
				String.valueOf(Math.max(BlockChainConfig.blockEpochSecond, BlockChainConfig.blockEpochMSecond)))));
		if (FastByteComparisons.equal(curBlock.getMiner().getReward().toByteArray(), reward)) {
			Act.Account oAccount = accountHelper.addBalance(curBlock.getMiner().getAddress(),
					ByteUtil.bytesToBigInteger(curBlock.getMiner().getReward().toByteArray()));

			accountHelper.putAccountValue(oAccount.getAddress(), oAccount.getValue());
		}
	}

	/**
	 * 创建新Block
	 *
	 * @param txs
	 *            交易集合
	 * @param extraData
	 *            附加数据
	 * @param term
	 *            当前块所在的Term
	 * @return newBlock
	 * @throws BlockException
	 *             BlockException
	 */
	@Override
	public synchronized Block.BlockEntity createNewBlock(List<Tx.Transaction> txs, String extraData, String term,
			List<String> partAddress, String nodeBit) throws BlockException {
		StopWatch stopWatch = StopWatch.createStarted();
		Block.BlockEntity.Builder block = Block.BlockEntity.newBuilder();
		Block.BlockHeader.Builder blockHeader = Block.BlockHeader.newBuilder();
		Block.BlockBody.Builder blockBody = Block.BlockBody.newBuilder();
		Block.BlockMiner.Builder blockMiner = Block.BlockMiner.newBuilder();

		// 获取最大块
		Block.BlockEntity bestBlock = blockChainHelper.GetConnectBestBlock();
		if (bestBlock == null) {
			bestBlock = blockChainHelper.GetStableBestBlock();
		}
		Block.BlockHeader bestBlockHeader = bestBlock.getHeader();
		blockHeader.setPreHash(bestBlockHeader.getHash());

		long curTime = System.currentTimeMillis();
		blockHeader.setTimestamp(curTime == bestBlockHeader.getTimestamp() ? (curTime + 1) : curTime);
		blockHeader.setNumber(bestBlockHeader.getNumber() + 1);
		blockHeader.setExtData(ByteString.copyFromUtf8(extraData));
		// Block设置Slice, 设置SSR值, 如果是第一块, 则初始化ssr[0,0,0,0]
		blockHeader.setSliceId(accountHelper.getSliceTotal() <= 0 ? 0 : accountHelper.getCurrentSlice());
		if (accountHelper.getSliceTotal() > 0 && bestBlockHeader.getNumber() == 0) {
			IntStream.range(0, accountHelper.getSliceTotal()).forEach(i -> blockHeader.addSsr("0"));
		} else {
			blockHeader.addAllSsr(bestBlock.getHeader().getSsrList());
		}

		// 添加交易Hash
		txs.forEach(t -> blockHeader.addTxHashs(t.getHash()));

		// 添加Miner信息
		NodeDef node = blockChainHelper.getNode();
		blockMiner.setAddress(node.getoAccount().getAddress());
		blockMiner.setBcuid(node.getBcuid());
		blockMiner.setReward(ByteString
				.copyFrom(ByteUtil.bigIntegerToBytes(BlockChainConfig.minerReward.multiply(new BigInteger(String
						.valueOf(Math.max(BlockChainConfig.blockEpochSecond, BlockChainConfig.blockEpochMSecond)))))));
		if (StringUtils.isNotBlank(term)) {
			blockMiner.setTermid(term);
		}
		if (partAddress != null && partAddress.size() > 0) {
			blockMiner.addAllParts(partAddress);
		}
		if (StringUtils.isNotBlank(nodeBit)) {
			blockMiner.setBit(nodeBit);
		}

		// 设置Block[header, body, Miner]
		block.setHeader(blockHeader);
		block.setBody(blockBody);
		block.setMiner(blockMiner);
		block.setVersion(BlockChainConfig.accountVersion);

		try {
			// 处理Block
			Blockimpl.AddBlockResponse.Builder response = Blockimpl.AddBlockResponse.newBuilder();
			processBlock(block, bestBlock, response, txs);
			// 更新当前block下, ssr分片的值
			if (accountHelper.getCurrentSlice() > 0
					&& accountHelper.getSliceTotal() >= accountHelper.getCurrentSlice()) {
				block.getHeaderBuilder().setSsr(accountHelper.getCurrentSlice() - 1,
						convert(block.getHeaderBuilder().getStateRoot()));
			}

			// 处理交易返回结果
			if (response.getTxHashsCount() > 0) {
				log.warn("must sync tx first, need count:: {}", response.getTxHashsCount());
				return null;
			}
			// BlockHash(blockContent)
			byte[] blockContent = org.csc.account.util.ByteUtil.appendBytes(
					block.getHeaderBuilder().clearHash().build().toByteArray(), blockMiner.build().toByteArray());
			block.setHeader(block.getHeaderBuilder().setHash(ByteString.copyFrom(encApi.sha256Encode(blockContent))));

			// 创建Block, connectBlock
			BlockStoreSummary summary = blockChainHelper.addBlock(block.build());
			if (summary.getBehavior().name().equals(BlockStoreSummary.BLOCK_BEHAVIOR.APPLY.name())) {
				blockChainHelper.connectBlock(block.build());

				log.info("new block, number::" + block.getHeader().getNumber() + " hash::"
						+ convert(block.getHeader().getHash()) + " parent::" + convert(block.getHeader().getPreHash())
						+ " tx::" + convert(block.getHeader().getTxRoot()) + " state::"
						+ convert(block.getHeader().getStateRoot()) + " receipt::"
						+ convert(block.getHeader().getReceiptRoot()) + " bcuid::" + " address::"
						+ convert(blockMiner.getAddress()) + " headerTx::" + block.getHeader().getTxHashsCount()
						+ " bodyTx::" + block.getBody().getTxsCount());
				return block.build();
			} else {
				log.error("error on create block::" + block.getHeader().getNumber() + " action=" + summary);
			}
		} catch (BlockStateTrieRuntimeException e) {
			log.error("block need to roll back::{}", e.getMessage(), e);
			blockChainHelper.rollbackTo(bestBlockHeader.getNumber() - 10);
		} catch (Exception e) {
			log.error("error on create block:", e);
			throw new BlockException(e);
		} finally {
			stopWatch.stop();
			log.info("====> end create block number::" + block.getHeader().getNumber() + " cost::"
					+ stopWatch.getTime(TimeUnit.MILLISECONDS) + " txs::" + block.getHeader().getTxHashsCount());
		}

		return null;
	}

	@AllArgsConstructor
	class ParalTxLoader implements Runnable {
		ByteString txHash;
		int dstIndex;
		CountDownLatch cdl;
		Tx.Transaction[] bb;
		byte[][] txTrieBB;
		Map<ByteString, Act.Account.Builder> accounts;
		ConcurrentLinkedQueue<ByteString> missingHash;
		long number;
		AtomicBoolean justCheck;

		@Override
		public void run() {
			try {
				Thread.currentThread().setName("txLoader-" + number);
				HashPair hp = transactionHelper.removeWaitingSendOrBlockTx(txHash);
				Tx.Transaction tx = null;
				if (hp != null) {
					tx = hp.getTx();
				}

				tx = tx == null ? transactionHelper.GetTransaction(txHash) : tx;
				if (tx == null || tx.getHash().isEmpty() || tx.getBody().getInput() == null) {
					missingHash.add(txHash);
					justCheck.set(true);
				} else {
					if (!justCheck.get()) {
						bb[dstIndex] = tx;
						txTrieBB[dstIndex] = transactionHelper.getTransactionContent(tx);
						transactionHelper.merageTransactionAccounts(tx, accounts);
					}
				}
			} catch (Exception e) {
				log.error("error in loading tx: {}, idx={}", convert(txHash), dstIndex, e);
			} finally {
				cdl.countDown();
				Thread.currentThread().setName("stateTrie-pool");
			}
		}
	}

	/**
	 * 处理Block
	 *
	 * @param block
	 *            block
	 * @param parentBlock
	 *            parentBlock
	 * @param response
	 *            response
	 * @param createdTxs
	 *            txs
	 * @return true/false
	 * @throws Exception
	 *             Exception
	 */
	private synchronized boolean processBlock(Block.BlockEntity.Builder block, Block.BlockEntity parentBlock,
			Blockimpl.AddBlockResponse.Builder response, List<Tx.Transaction> createdTxs) throws Exception {
		// CacheTrie oTransactionTrie = new CacheTrie(this.encApi);
//		EMTree64 oTransactionTrie = new EMTree64(new DefaultStorage64());
		EMTree64 oReceiptTrie = new EMTree64(new DefaultStorage64());

		try {
			Block.BlockHeader.Builder blockHeader = block.getHeader().toBuilder();
			byte[][] txTrieBB = new byte[blockHeader.getTxHashsCount()][];
			Tx.Transaction[] txs = new Tx.Transaction[blockHeader.getTxHashsCount()];
			Map<ByteString, Act.Account.Builder> accounts = new ConcurrentHashMap<>(blockHeader.getTxHashsCount());
			boolean isCreated = createdTxs != null;
			int slice = blockHeader.getSliceId();
			long number = blockHeader.getNumber();

			clearStateRoot(number, slice, parentBlock);
			long start = System.currentTimeMillis();

			// 创建Block
			if (isCreated) {
				transactionHelper.merageSystemAccounts(accounts);
				for (int dstIndex = 0; dstIndex < createdTxs.size(); dstIndex++) {
					Tx.Transaction oMultiTransaction = createdTxs.get(dstIndex);
					txs[dstIndex] = oMultiTransaction;
					txTrieBB[dstIndex] = transactionHelper.getTransactionContent(oMultiTransaction);
					transactionHelper.merageTransactionAccounts(oMultiTransaction, accounts);
				}
			} else {// 同步block
				AtomicBoolean justCheck = new AtomicBoolean(false);
				int count = blockHeader.getTxHashsCount();
				ConcurrentLinkedQueue<String> lackHash  = new ConcurrentLinkedQueue<>();
//				List<Future<String>> futures = Lists.newArrayListWithCapacity(count);
				CountDownLatch cdl = new CountDownLatch(count);
				transactionHelper.merageSystemAccounts(accounts);
				for (int i = 0; i < count; i++) {
					executor.submit(new ParallelTxLoader(blockHeader.getTxHashs(i), i, txs, txTrieBB,
							accounts, number, justCheck, transactionHelper, mts.getFastAddrByTxid(),lackHash,cdl));
				}

				cdl.await();
//				List<String> lackHash = futures.parallelStream().map(f -> {
//					try {
//						return f.get();
//					} catch (Exception e) {
//						log.error("===>Handler Tx fail: ", e);
//						return e.getMessage();
//					}
//				}).filter(s -> s != null && StringUtils.isNoneBlank(s)).collect(Collectors.toList());

				if (!lackHash.isEmpty()) {
					// for test
					response.addAllTxHashs(lackHash);
					return false;
				}
			}

			int accountSize = accounts.size();

			// 执行交易
			long endPrepareTx = System.currentTimeMillis();
			Block.BlockHeader.Builder header = block.getHeaderBuilder();

			header.clearTxr();
			List<BlockTransactionResult> results = executeTx(txs, block.build(), accounts);
			long endExecTx = System.currentTimeMillis();

			// 主要处理同步Block时, 如果有交易失败的记录, 需要设置Tx状态为失败
			// handlerReceipt(results, block, txs, header.getTxrList(), isCreated);

			if (log.isDebugEnabled()) {
				log.debug("===>BlockTransactionResult: {}", results.size());
				results.forEach(e -> log.debug("===>Index: {}, status: {}", e.getI(), e.getStatus()));
			}

			// Tx树
			// Block.BlockBody.Builder bb = block.getBody().toBuilder();
			// Block.BlockBody.Builder bb = Block.BlockBody.newBuilder();
			//for (int i = 0; i < blockHeader.getTxHashsCount(); i++) {// trips.
				// bb.addTxs(txs[i]);
				//oTransactionTrie.put(RLP.encodeInt(i), txTrieBB[i]);
			//}

			// 统计信息
			transactionHelper.getStats().signalBlockTx(blockHeader.getTxHashsCount());
			// block.setBody(bb);

			// 交易执行结果排序
			oReceiptTrie.put(RLP.encodeInt(1),"OK".getBytes());
//			for (int i = 0; i < results.size() && i < 10; i++) {
//				BlockTransactionResult e = results.get(i);
//				oReceiptTrie.put(RLP.encodeInt(e.getI()), e.getResult().getBytes());
//			}
			// results.forEach(e -> oReceiptTrie.put(RLP.encodeInt(e.getI()),
			// e.getResult().getBytes()));
			header.addAllTxr(results);

			// 矿工奖励
			applyReward(block);
			int blocknumber = (int) block.getHeader().getNumber();
			// Debug模式下, 打印凭证树hash是否一致
			if (log.isDebugEnabled()) {
				ByteString receipt = header.getReceiptRoot();
				String newReceipt = encApi.hexEnc(oReceiptTrie.encode(blocknumber) == null ? ByteUtil.EMPTY_BYTE_ARRAY
						: oReceiptTrie.encode(blocknumber));
				log.debug("===>Origin: {}, new: {}", convert(receipt), newReceipt);
			}

			// 凭证树, 交易树, 账户树
			header.setReceiptRoot(
					ByteString.copyFrom(oReceiptTrie.encode(blocknumber) == null ? ByteUtil.EMPTY_BYTE_ARRAY
							: oReceiptTrie.encode(blocknumber)));
			// header.setTxRoot(
			//		ByteString.copyFrom(oTransactionTrie.encode(blocknumber) == null ? ByteUtil.EMPTY_BYTE_ARRAY
			//				: oTransactionTrie.encode(blocknumber)));

			long endTxTrie = System.currentTimeMillis();

			// 如果不再该分片上生成的block, 则不更新stateRoot.
			if (accountHelper.canExecute(slice)) {
				byte[] bytes = this.stateTrie.getRootHash(blocknumber);
				rootHash = bytes;
				header.setStateRoot(ByteString.copyFrom(bytes));
			}
			block.setHeader(header.build());

			log.error("loadtest ==> end exec block height=" + header.getNumber() + " txsize="
					+ block.getHeader().getTxHashsCount() + " accsize=" + accountSize + " total="
					+ (System.currentTimeMillis() - start) + " prepare=" + (endPrepareTx - start) + " exec="
					+ (endExecTx - endPrepareTx) + " txtrie=" + (endTxTrie - endExecTx) + " statetrie="
					+ (System.currentTimeMillis() - endTxTrie));
		} catch (Exception e) {
			log.error("===>processBlock: {}", e);
			throw e;
		} finally {
//			oTransactionTrie = null;
			oReceiptTrie = null;
		}
		return true;
	}

	/**
	 * 针对分片节点, 是否需要清理Root, 重新验证, 如果分片与Parent同分片
	 *
	 * @param number
	 *            当前Block高度
	 * @param slice
	 *            当前Block分片
	 * @param parentBlock
	 *            上一块
	 * @throws Exception
	 *             Exception
	 */
	private void clearStateRoot(long number, int slice, Block.BlockEntity parentBlock) throws Exception {
		int preSlice = Long.valueOf(parentBlock.getHeader().getSliceId()).intValue();
		boolean isClear = number == 1 || accountHelper.getSliceTotal() < 0
				|| (accountHelper.getCurrentSlice() == preSlice && accountHelper.getCurrentSlice() == slice);

		byte[] preStateRoot = parentBlock.getHeader().getStateRoot().toByteArray();

		if (rootHash == null || (isClear && !Arrays.equals(rootHash, preStateRoot))) {
			rootHash = preStateRoot;
			log.error("reset state root height=" + number + " current.root="
					+ (rootHash == null ? "" : encApi.hexEnc(rootHash)) + " block.root="
					+ encApi.hexEnc(parentBlock.getHeader().getStateRoot().toByteArray()));

			this.stateTrie.clear();
			this.stateTrie.setRoot(preStateRoot);
		}
	}

	/**
	 * 主要处理同步Block时, 如果有交易失败的记录, 需要设置Tx状态为失败
	 *
	 * @param results
	 *            本节点执行结果
	 * @param block
	 *            同步的Block
	 * @param origin
	 *            block中Tx执行结果
	 * @param isCreated
	 *            是否是新建Block
	 */
	private void handlerReceipt(List<Block.BlockTransactionResult> results, Block.BlockEntity.Builder block,
			Tx.Transaction[] txs, List<Block.BlockTransactionResult> origin, boolean isCreated) {
		long slice = block.getHeader().getSliceId();
		// 如果是创建Block/不分片/打块者执行交易成功/同分片, 直接返回
		if (isCreated || accountHelper.getSliceTotal() == -1 || origin == null || origin.isEmpty()
				|| slice == accountHelper.getCurrentSlice()) {
			return;
		}

		if (log.isDebugEnabled()) {
			log.debug("===>origin: {}, txs: {}", origin.size(), txs.length);
		}

		// 处理同步Block, 设置本地交易为失败状态
		origin.stream().filter(t -> "E".equals(t.getStatus())).forEach(t -> {
			Optional<Block.BlockTransactionResult> o = results.stream().filter(e -> e.getI() == t.getI()).findFirst();
			if (!o.isPresent()) {
				results.add(t);
				transactionHelper.setTransactionError(txs[t.getI()], block.build(),
						ByteString.copyFromUtf8(t.getResult()));
				// TXStatus.setError(txs[t.getI()].toBuilder(), t.getResult());
			}
		});
	}

	/**
	 * 同步Block
	 *
	 * @param block
	 *            当前block
	 * @param fastApply
	 *            追block用,新节点启动, 追块
	 * @return AddBlockResponse
	 */
	@Override
	public synchronized Blockimpl.AddBlockResponse applyBlock(Block.BlockEntity.Builder block, boolean fastApply) {
		log.error("receive block number::{}, hash:{}, address:{}, Txs:{}", block.getHeader().getNumber(),
				convert(block.getHeader().getHash()), convert(block.getMiner().getAddress()),
				block.getBody().getTxsCount());

		Thread.currentThread().setName("v4Apply-" + block.getHeader().getNumber());
		Block.BlockEntity.Builder applyBlock = block.clone();
		StopWatch stopWatch = StopWatch.createStarted();
		Blockimpl.AddBlockResponse.Builder response = Blockimpl.AddBlockResponse.newBuilder();

		try {
			Block.BlockHeader.Builder blockHeader = Block.BlockHeader.parseFrom(block.getHeader().toByteArray())
					.toBuilder();
			blockHeader.clearHash();

			byte[] contents = ByteUtil.appendBytes(blockHeader.build().toByteArray(), block.getMiner().toByteArray());
			byte[] blockHash = encApi.sha256Encode(contents);
			// 验证BlockHash
			if (!FastByteComparisons.equal(blockHash, block.getHeader().getHash().toByteArray())) {
				log.warn("wrong block hash:: {}, need:: {}}", convert(block.getHeader().getHash()),
						encApi.hexEnc(blockHash));
			} else {
				BlockStoreSummary summary = blockChainHelper.addBlock(block.build());
				while (summary.getBehavior() != BlockStoreSummary.BLOCK_BEHAVIOR.DONE) {
					switch (summary.getBehavior()) {
					case DROP:
						log.warn("drop block number::" + applyBlock.getHeader().getNumber());
						summary.setBehavior(BlockStoreSummary.BLOCK_BEHAVIOR.DONE);
						break;
					case EXISTS_DROP:
						log.warn("already exists, try to apply::" + applyBlock.getHeader().getNumber());
						summary.setBehavior(blockChainHelper.tryAddBlock(applyBlock.build()).getBehavior());
						break;
					case EXISTS_PREV:
						log.warn("block exists, but cannot find parent block number::"
								+ applyBlock.getHeader().getNumber());
						try {
							long rollBackNumber = applyBlock.getHeader().getNumber() - 2;
							// log.debug("need prev block number::" +
							// rollBackNumber);
							response.setRetCode(-9);
							response.setCurrentNumber(rollBackNumber);
							response.setWantNumber(rollBackNumber + 1);

							// 回滚到当前block, setRoot新加
							Block.BlockEntity rollbackBlock = blockChainHelper.rollbackTo(rollBackNumber);
							if (rollbackBlock != null) {
								log.debug("=>RollBlock.slice:{}, stateRoot:{}", rollbackBlock.getHeader().getSliceId(),
										convert(rollbackBlock.getHeader().getStateRoot()));
								if (accountHelper.getSliceTotal() > 0
										&& rollbackBlock.getHeader().getSliceId() == accountHelper.getCurrentSlice()) {
									stateTrie.clear();
									rootHash = rollbackBlock.getHeader().getStateRoot().toByteArray();
									stateTrie.setRoot(rollbackBlock.getHeader().getStateRoot().toByteArray());
								}
								// setBehavior
							}
							summary.setBehavior(BlockStoreSummary.BLOCK_BEHAVIOR.DONE);
						} catch (Exception e1) {
							log.error("exception ", e1);
							summary.setBehavior(BlockStoreSummary.BLOCK_BEHAVIOR.ERROR);
						}
						break;
					case CACHE:
						log.warn("cache block number::" + applyBlock.getHeader().getNumber());
						response.setWantNumber(applyBlock.getHeader().getNumber());
						summary.setBehavior(BlockStoreSummary.BLOCK_BEHAVIOR.DONE);
						break;
					case APPLY:
						Block.BlockEntity parentBlock = blockChainHelper
								.getBlockByHash(applyBlock.getHeader().getPreHash());
						if (fastApply && applyBlock.getBody().getTxsCount() > 0) {
							applyBlock.getBody().getTxsList().forEach(
									t -> transactionHelper.syncTransaction(t.toBuilder(), false, BigInteger.ZERO));
						}

						processBlock(applyBlock, parentBlock, response, null);

						if (log.isDebugEnabled()) {
							log.debug("===>state:{},newState:{}", convert(block.getHeader().getStateRoot()),
									convert(applyBlock.getHeader().getStateRoot()));
						}

						if (response.getTxHashsCount() > 0) {
							summary.setBehavior(BlockStoreSummary.BLOCK_BEHAVIOR.ERROR);
							response.setWantNumber(applyBlock.getHeader().getNumber());
							log.warn("must sync tx first, need count::" + response.getTxHashsCount());
							break;
						}

						// BlockBody.Builder bbb = BlockBody.newBuilder();
						// for (Transaction tx : applyBlock.getBody().getTxsList()) {
						//	bbb.addTxs(Transaction.newBuilder().setHash(tx.getHash()));
						//}
						//applyBlock.setBody(bbb);

						// 针对applyBlock, 如果不是本分片打的Block, 不需要验证, 同分片需要验证
						long sliceId = blockHeader.getSliceId();
						boolean isRollBack = accountHelper.canExecute(Long.valueOf(sliceId).intValue())
								&& (!Arrays.equals(block.getHeader().getStateRoot().toByteArray(),
										applyBlock.getHeader().getStateRoot().toByteArray())
										|| !Arrays.equals(block.getHeader().getTxRoot().toByteArray(),
												applyBlock.getHeader().getTxRoot().toByteArray())
										|| !Arrays.equals(block.getHeader().getReceiptRoot().toByteArray(),
												applyBlock.getHeader().getReceiptRoot().toByteArray()));
						if (isRollBack) {
							log.error("begin to roll back, stateRoot::" + convert(block.getHeader().getStateRoot())
									+ " blockStateRoot::" + convert(applyBlock.getHeader().getStateRoot()) + " txRoot::"
									+ convert(block.getHeader().getTxRoot()) + " blockTxRoot::"
									+ convert(applyBlock.getHeader().getTxRoot()) + " receiveRoot::"
									+ convert(block.getHeader().getReceiptRoot()) + " blockReceiveRoot::"
									+ convert(applyBlock.getHeader().getReceiptRoot()));

							transactionHelper.getStats().getRollBackBlockCount().incrementAndGet();
							transactionHelper.getStats().getRollBackTxCount().incrementAndGet();
							transactionHelper.getStats().signalBlockTx(-applyBlock.getHeader().getTxHashsCount());

							// 回滚到当前block(applyBlock.number - 2)防止vrf不回滚, setRoot新加
							Block.BlockEntity rollbackBlock = blockChainHelper.rollbackTo(applyBlock.getHeader().getNumber() - 2);
							if(log.isDebugEnabled()){
								log.debug("=>RollBlock.slice:{}, stateRoot:{}", rollbackBlock.getHeader().getSliceId(),
										convert(rollbackBlock.getHeader().getStateRoot()));
							}
							if (accountHelper.getSliceTotal() > 0
									&& rollbackBlock.getHeader().getSliceId() == accountHelper.getCurrentSlice()) {
								stateTrie.clear();
								rootHash = rollbackBlock.getHeader().getStateRoot().toByteArray();
								stateTrie.setRoot(rollbackBlock.getHeader().getStateRoot().toByteArray());
							}

							final Block.BlockHeader.Builder bbh = blockHeader;
							executor.submit(() -> bbh.getTxHashsList().forEach(h -> oConfirmMapDB.revalidate(h)));
							summary.setBehavior(BlockStoreSummary.BLOCK_BEHAVIOR.ERROR);
						} else {
							summary = blockChainHelper.connectBlock(applyBlock.build());
							//final long blockNumber = applyBlock.getHeader().getNumber();
							//executor.submit(() -> oConfirmMapDB.clear(blockNumber));
							oConfirmMapDB.clear(applyBlock.getHeader().getNumber());
						}
						break;
					case APPLY_CHILD:
						List<Block.BlockEntity> children = blockChainHelper.getChildBlock(applyBlock.build());
						for (Block.BlockEntity blockEntity : children) {
							applyBlock = blockEntity.toBuilder();
							log.warn("ready to apply child block::" + convert(applyBlock.getHeader().getHash())
									+ " number::" + applyBlock.getHeader().getNumber());
							applyBlock(applyBlock, fastApply);
						}
						summary.setBehavior(BlockStoreSummary.BLOCK_BEHAVIOR.DONE);
						break;
					case STORE:
						log.warn("apply done number::" + blockChainHelper.getLastBlockNumber());
						summary.setBehavior(BlockStoreSummary.BLOCK_BEHAVIOR.DONE);
						break;
					case ERROR:
						log.warn("fail to apply block number::" + applyBlock.getHeader().getNumber() + ":want="
								+ response.getWantNumber() + ",needTxHash=" + response.getTxHashsCount() + ",ApplyHash="
								+ convert(applyBlock.getHeader().getHash()));
						summary.setBehavior(BlockStoreSummary.BLOCK_BEHAVIOR.DONE);
						break;
					default:
						log.warn("ApplyBlock BlockStoreSummary behavior fail.");
					}
				}
			}
		} catch (BlockStateTrieRuntimeException e) {
			log.error("block need to roll back " + e.getMessage(), e);
			blockChainHelper.rollbackTo(applyBlock.getHeader().getNumber() - 2);
		} catch (Exception e2) {
			log.error("error on validate block header::" + e2, e2);
		}

		if (response.getCurrentNumber() == 0) {
			response.setCurrentNumber(blockChainHelper.getLastBlockNumber());
		}

		if (response.getWantNumber() == 0) {
			response.setWantNumber(response.getCurrentNumber());
		}

		stopWatch.stop();
		log.info("====> end apply block number::" + block.getHeader().getNumber() + " cost::"
				+ stopWatch.getTime(TimeUnit.MILLISECONDS) + " txs::" + block.getHeader().getTxHashsCount());
		transactionHelper.getStats().setCurBlockID(block.getHeader().getNumber());
		return response.build();
	}
}
