package org.csc.account.processor;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.osgi.annotation.iPojoBean;
import onight.tfw.ntrans.api.ActorService;
import onight.tfw.ntrans.api.annotation.ActorRequire;
import onight.tfw.outils.conf.PropHelper;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Invalidate;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Validate;
import org.csc.account.api.*;
import org.csc.account.api.enums.TransTypeEnum;
import org.csc.account.bean.HashPair;
import org.csc.account.bean.StatsInfo;
import org.csc.account.bean.TXStatus;
import org.csc.account.core.actuator.AbstractTransactionActuator;
import org.csc.account.core.actuator.ActuatorFactory;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.TransactionException;
import org.csc.account.gens.Tximpl.*;
import org.csc.account.util.ByteUtil;
import org.csc.account.util.FastByteComparisons;
import org.csc.account.util.NodeDef;
import org.csc.account.util.OEntityBuilder;
import org.csc.bcapi.EncAPI;
import org.csc.bcapi.backend.ODBException;
import org.csc.bcapi.gens.Oentity.OKey;
import org.csc.bcapi.gens.Oentity.OPair;
import org.csc.bcapi.gens.Oentity.OValue;
import org.csc.evmapi.gens.Act.Account;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx;
import org.csc.evmapi.gens.Tx.*;
import org.csc.rcvm.utils.RLP;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Arrays.copyOfRange;
import static org.csc.account.processor.BlockChainConfig.isDisableEC;

/**
 * @author
 */
@iPojoBean
@Provides(specifications = {ActorService.class})
@Instantiate(name = "Transaction_Helper")
@Slf4j
@Data
public class TransactionHelper implements ITransactionHelper, ActorService, Runnable {
    @ActorRequire(name = "Account_Helper", scope = "global")
    IAccountHelper oAccountHelper;
    @ActorRequire(name = "txstore", scope = "global")
    ITxStore txStore;
    @ActorRequire(name = "bc_encoder", scope = "global")
    EncAPI encApi;
    // 保存待广播交易
    @ActorRequire(name = "WaitSend_HashMapDB", scope = "global")
    IWaitForSendMap oSendingHashMapDB;
    @ActorRequire(name = "BlockChain_Helper", scope = "global")
    IChainHelper blockChainHelper;
    // 保存待打包block的交易
    // WaitBlockHashMapDB oPendingHashMapDB;
    // 保存待打包block的交易
    @ActorRequire(name = "ConfirmTxHashDB", scope = "global")
    IConfirmTxMap oConfirmMapDB;

    @ActorRequire(name = "ETBlock_StateTrie", scope = "global")
    IStateTrie stateTrie;
    @ActorRequire(name = "OEntity_Helper", scope = "global")
    OEntityBuilder oEntityHelper;
    PropHelper prop = new PropHelper(null);
    Cache<ByteString, Transaction> txDBCacheByHash = CacheBuilder.newBuilder()
            .initialCapacity(prop.get("org.csc.account.cache.tx.init", 10000)).expireAfterAccess(600, TimeUnit.SECONDS)
            .maximumSize(prop.get("org.csc.account.cache.tx.max", 100000))
            .concurrencyLevel(Runtime.getRuntime().availableProcessors()).build();

    @ActorRequire(name = "TxPendingQueue", scope = "global")
    IPengingQueue<HashPair> queue;

    StatsInfo stats = new StatsInfo();

    @Override
    public long getSendingSize() {
        return oSendingHashMapDB.size();
    }

    public boolean isStop = false;

    @Invalidate
    @Override
    public void invalid() {
        isStop = true;
        synchronized (this) {
            try {
                this.notifyAll();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (queue != null) {
            queue.shutdown();
        }
    }

    @Validate
    public void startup() {
        log.info("about to start Tx");
        new Thread(this).start();
    }

    @Override
    public void run() {
        try {
            // boot up wait
            Thread.sleep(3000);
        } catch (InterruptedException e1) {
            e1.printStackTrace();
        }

        while (!isStop) {
            try {
                // maxElementsInMemory=1000000, queueSize=confirmQueue.size
                if (oConfirmMapDB != null 
                    && oConfirmMapDB.size() < oConfirmMapDB.getMaxElementsInMemory()
                    && oConfirmMapDB.getQueueSize() < oConfirmMapDB.getMaxElementsInMemory()) {
                    List<HashPair> hps = queue
                            .poll(Math.min(oConfirmMapDB.getMaxElementsInMemory() - oConfirmMapDB.size(), 10000));
                    if (hps != null && hps.size() > 0) {
                        for (HashPair hp : hps) {
                            oSendingHashMapDB.put(hp.getKey(), hp);
                            oConfirmMapDB.confirmTx(hp, BigInteger.ZERO);
                        }
                        getStats().signalAcceptTx(hps.size());
                    }
                } /*else {
                    Thread.yield();
                }*/
            } catch (Exception e) {
                log.error("error in tx to pending::", e);
            }
            try {
                if (queue.hasMoreElement()) {
                    Thread.sleep(500);
                } else {
                    Thread.sleep(2000);
                }
            } catch (Throwable e) {
                log.error("error in tx to pending:", e);
            }
        }
    }

    /**
     * 保存交易方法。 交易不会立即执行，而是等待被广播和打包。只有在Block中的交易，才会被执行。 交易签名规则 1. 清除signatures 2.
     * txHash=ByteString.EMPTY 3. 签名内容=oMultiTransaction.toByteArray()
     *
     * @param tx
     * @throws BlockException
     */
    @Override
    public HashPair CreateMultiTransaction(Transaction.Builder tx) throws BlockException {
        NodeDef node = blockChainHelper.getNode();
        if (node == null) {
            return null;
        }

        TransactionNode.Builder oNode = TransactionNode.newBuilder();
        oNode.setAddress(node.getoAccount().getAddress());
        oNode.setBcuid(node.getBcuid());
        tx.setNode(oNode);

        HashPair pair = verifyAndSaveMultiTransaction(tx);
        pair.setNeedBroadCast(true);
        queue.addElement(pair);

        // log.info("{} account create transaction 创建交易[{}]", node.getNode(),
        // encApi.hexEnc(pair.getKeyBytes()));
        return pair;
    }

    @Override
    public String CreateGenesisMultiTransaction(Transaction.Builder tx) throws BlockException {
        tx.clearStatus();
        tx.clearHash();
        // 生成交易Hash
        tx.setHash(ByteString.copyFrom(encApi.sha256Encode(tx.getBody().toByteArray())));

        if (isExistsTransaction(tx.getHash())) {
            throw new BlockException("transaction exists, drop it hash::" + byte2String(tx.getHash()));
        }
        TXStatus.setDone(tx, null);
        Transaction transaction = tx.build();
        // 保存交易到db中
        getStats().signalAcceptTx(1);

        txStore.put(OEntityBuilder.byteKey2OKey(transaction.getHash()),
                OEntityBuilder.byteValue2OValue(transaction.toByteArray()));
        return byte2String(transaction.getHash());
    }

    private String byte2String(ByteString hash) {
        return encApi.hexEnc(hash.toByteArray());
    }

    /**
     * 广播交易方法。 交易广播后，节点收到的交易会保存在本地db中。交易不会立即执行，而且不被广播。只有在Block中的交易，才会被执行。
     *
     * @param tx 交易
     * @throws BlockException
     */
    @Override
    public void syncTransaction(Transaction.Builder tx, BigInteger bits) throws BlockException {
        syncTransaction(tx, true, bits);
    }

    @Override
    public void syncTransaction(Transaction.Builder tx) throws BlockException {
        syncTransaction(tx, true, zeroBits);
    }

    BigInteger zeroBits = new BigInteger("0");

    @Override
    public void syncTransaction(Transaction.Builder tx, boolean isBroadCast) {
        syncTransaction(tx, isBroadCast, zeroBits);
    }

    /**
     * 交易同步
     *
     * @param tx          交易
     * @param isBroadCast 是否广播
     * @param bits        确认的bits
     */
    @Override
    public void syncTransaction(Transaction.Builder tx, boolean isBroadCast, BigInteger bits) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("sync tx count x::{}", byte2String(tx.getHash()));
            }
            Transaction cacheTx = GetTransaction(tx.getHash());
            if (cacheTx != null) {
                return;
            }

            tx.clearStatus();
            tx.clearResult();

            //同步交易注释验签
			/*ITransactionActuator actuator = getActuator(tx.getBody().getType(), blockChainHelper.GetConnectBestBlock());
			if (actuator.needSignature()) {
				Map<ByteString, Account.Builder> accounts = null;
				if (tx.getBody().getType() == TransTypeEnum.TYPE_UnionAccountTransaction.value()
						|| tx.getBody().getType() == TransTypeEnum.TYPE_UnionAccountTokenTransaction.value()
						|| tx.getBody().getType() == TransTypeEnum.TYPE_sideChainSync.value()) {
					accounts = getTransactionAccounts(tx);
				}

				actuator.onVerifySignature(tx.build(), accounts);
			}*/

            byte[] keyByte = tx.getHash().toByteArray();
            OKey key = OEntityBuilder.byteKey2OKey(keyByte);
            if (!isDisableEC) {
                byte[] validKeyByte = encApi.sha256Encode(tx.getBody().toByteArray());
                if (!FastByteComparisons.equal(validKeyByte, keyByte)) {
                    log.error("fail to sync transaction::{}, content invalid, Tx: {}", byte2String(tx.getHash()), tx);
                    return;
                }
            }

            Transaction mt = tx.build();
            HashPair hp = new HashPair(mt.getHash(), mt.toByteArray(), mt);
            hp.setNeedBroadCast(isBroadCast);
            OValue.Builder oValue = OValue.newBuilder().setExtdata(ByteString.copyFrom(hp.getData()))
                    .setInfo(byte2String(mt.getHash()));
            txStore.put(key, oValue.build());
            txDBCacheByHash.put(hp.getKey(), hp.getTx());

            getStats().signalSyncTx();
            if (isBroadCast) {
                oConfirmMapDB.confirmTx(hp, bits);
            }
        } catch (Exception e) {
            log.error("fail to sync transaction::" + tx.getHash() + " error::" + e, e);
        }
    }

    @Override
    public void syncTransactionBatch(List<Transaction.Builder> oMultiTransaction, BigInteger bits)
            throws BlockException {
        syncTransactionBatch(oMultiTransaction, true, bits);
    }

    @Override
    public boolean containConfirm(ByteString hash, int bit) {
        HashPair hp = oConfirmMapDB.getHP(hash);
        if (hp != null) {
            return hp.getBits().testBit(bit);
        }
        return false;
    }

    /**
     * 批量同步交易(注释交易验签)
     *
     * @param txs         同步交易记录
     * @param isBroadCast 是否广播
     * @param bits        bits
     */
    @Override
    public void syncTransactionBatch(List<Transaction.Builder> txs, boolean isBroadCast, BigInteger bits) {
        if (txs == null || txs.isEmpty()) {
            return;
        }

        List<OKey> keys = new ArrayList<>();
        List<OValue> values = new ArrayList<>();
        for (Transaction.Builder mtb : txs) {
            try {
                Transaction cacheTx = GetTransaction(mtb.getHash());
                boolean isDone = false;
                if (cacheTx != null) {
                    isDone = TXStatus.isDone(cacheTx);
                }

                if (!isDone) {
                    Transaction mt = mtb.clearStatus().clearResult().build();
                    if (cacheTx == null) {
						/*ITransactionActuator oITransactionActuator = getActuator(mt.getBody().getType(),
								blockChainHelper.GetConnectBestBlock());
						if (oITransactionActuator.needSignature()) {
							Map<ByteString, Account.Builder> accounts = null;
							// 联合账户交易, 联合账户token交易, 主子同步需要验签
							if (mt.getBody().getType() == TransTypeEnum.TYPE_UnionAccountTransaction.value()
									|| mt.getBody().getType() == TransTypeEnum.TYPE_UnionAccountTokenTransaction.value()
									|| mt.getBody().getType() == TransTypeEnum.TYPE_sideChainSync.value()) {
								accounts = getTransactionAccounts(mt);
							}
							oITransactionActuator.onVerifySignature(mt, accounts);
						}*/
                        ByteString mts = mt.toByteString();
                        HashPair hp = new HashPair(mt.getHash(), mts.toByteArray(), mt);
                        hp.setNeedBroadCast(false);
                        keys.add(OEntityBuilder.byteKey2OKey(mtb.getHash()));
                        values.add(OValue.newBuilder().setExtdata(mts).setInfo(byte2String(mtb.getHash())).build());
                        if (isBroadCast) {
                            oConfirmMapDB.confirmTx(hp, bits);
                        }
                        txDBCacheByHash.put(hp.getKey(), hp.getTx());
                        getStats().signalSyncTx();
                    }

                    oConfirmMapDB.confirmTx(mtb.getHash(), bits);
                }
            } catch (Exception e) {
                log.error("fail to sync transaction::" + txs.size() + " error::" + e, e);
            }
        }

        try {
            OKey[] ks = new OKey[keys.size()];
            OValue[] vs = new OValue[keys.size()];
            for (int i = 0; i < ks.length; i++) {
                ks[i] = keys.get(i);
                vs[i] = values.get(i);
            }

            // 返回DB里面不存在的,但是数据库已经存进去的
            txStore.batchPuts(ks, vs);
        } catch (Exception e) {
            log.error("fail to sync transaction::" + txs.size() + " error::" + e, e);
        }
    }

    @Override
    public BroadcastTransactionMsg getWaitSendTxToSend(int count) throws BlockException {
        BroadcastTransactionMsg.Builder oBroadcastTransactionMsg = BroadcastTransactionMsg.newBuilder();
        int total = 0;

        for (Iterator<Map.Entry<ByteString, HashPair>> it = oSendingHashMapDB.getStorage().entrySet().iterator(); it
                .hasNext(); ) {
            Map.Entry<ByteString, HashPair> item = it.next();
            oBroadcastTransactionMsg.addTxHash(item.getKey());
            oBroadcastTransactionMsg.addTxDatas(ByteString.copyFrom(item.getValue().getData()));
            it.remove();
            total += 1;
            if (count == total) {
                break;
            }
        }

        return oBroadcastTransactionMsg.build();
    }

    /**
     * 从待打包交易列表中，查询出等待打包的交易。
     *
     * @param count
     * @return
     * @throws BlockException
     */
    @Override
    public List<Transaction> getWaitBlockTx(int count, int confirmTimes) {
        StopWatch stopWatch = StopWatch.createStarted();
        List<Transaction> list = oConfirmMapDB.poll(count, confirmTimes);
        stopWatch.stop();
        // 同步交易时间
        if (log.isDebugEnabled()) {
            log.debug("===>Get wait Block Tx: {}, Cost: {}", list.size(), stopWatch.getTime(TimeUnit.MILLISECONDS));
        }
        return list;
    }

    /**
     * VRF确认次数 ConfirmRunner同步
     * @param key       hash
     * @param fromBits  bit
     */
    @Override
    public void confirmRecvTx(ByteString key, BigInteger fromBits) {
        try {
            Transaction otx = GetTransaction(key);
            if (otx != null && otx.getBody() != null) {
                if (TXStatus.isDone(otx)) {
                    return;
                }
            }
            oConfirmMapDB.confirmTx(key, fromBits);
        } catch (Exception e) {
            log.error("error in confirmRecvTx:" + byte2String(key) + ",fromBits=" + fromBits, e);
        }
    }

    @Override
    public HashPair removeWaitingSendOrBlockTx(ByteString hash) throws BlockException {
        HashPair hpBlk = oConfirmMapDB.invalidate(hash);
        HashPair hpSend = oSendingHashMapDB.getStorage().remove(hash);

        if (hpBlk != null && hpBlk.getTx() != null) {
            return hpBlk;
        } else {
            return hpSend;
        }
    }

    @Override
    public boolean isExistsWaitBlockTx(ByteString hash) throws BlockException {
        return oConfirmMapDB.containsKey(hash);
    }

    /**
     * 根据交易Hash，返回交易实体。
     *
     * @param hash
     * @return
     * @throws BlockException
     */
    @Override
    public Transaction GetTransaction(ByteString hash) throws BlockException {
        if (hash == null || hash.isEmpty()) {
            return null;
        }

        Transaction cacheTx = txDBCacheByHash.getIfPresent(hash);
        if (cacheTx != null) {
            return cacheTx;
        }

        Transaction tx;
        try {
            OValue oValue = txStore.get(OEntityBuilder.byteKey2OKey(hash)).get();

            if (oValue == null || oValue.getExtdata() == null) {
                return null;
            }
            tx = Transaction.parseFrom(oValue.getExtdata().toByteArray());
            txDBCacheByHash.put(hash, tx);
        } catch (ODBException | InvalidProtocolBufferException | ExecutionException | InterruptedException e) {
            throw new BlockException(e);
        }

        return tx;
    }

    /**
     * 获取交易签名后的Hash
     *
     * @param tx
     * @throws BlockException
     */
    @Override
    public void getTransactionHash(Transaction.Builder tx) throws BlockException {
        if (tx.getBody().getSignatures().isEmpty()) {
            throw new BlockException("交易需要签名后才能设置交易Hash");
        }

        tx.setHash(ByteString.copyFrom(encApi.sha256Encode(tx.build().toByteArray())));
    }

    /**
     * 获取input的地址
     *
     * @param tx    交易
     * @param index 索引
     * @return 地址
     */
    public String getInputAddress(Transaction tx, int index) {
        return tx.getBody().getInput().getAddress().toStringUtf8();
    }

    /**
     * 映射为接口类型
     *
     * @param tx
     * @return
     */
    @Override
    public MultiTransactionImpl.Builder parseToImpl(Transaction tx) {
        TransactionBody body = tx.getBody();

        MultiTransactionImpl.Builder txImpl = MultiTransactionImpl.newBuilder();
        txImpl.setTxHash(byte2String(tx.getHash()));
        txImpl.setStatus(tx.getStatus().isEmpty() ? "" : tx.getStatus().toStringUtf8());

        if (TXStatus.isDone(tx)) {
            txImpl.setResult(encApi.hexEnc(tx.getResult().toByteArray()));
        } else {
            txImpl.setResult(tx.getResult().toStringUtf8());
        }

        MultiTransactionBodyImpl.Builder bodyImpl = MultiTransactionBodyImpl.newBuilder();
        bodyImpl.setType(body.getType());
        bodyImpl.setData(encApi.hexEnc(body.getData().toByteArray()));
        bodyImpl.setExdata(encApi.hexEnc(body.getExtData().toByteArray()));

        // 发起者
        TransactionInput input = body.getInput();
        MultiTransactionInputImpl.Builder oMultiTransactionInputImpl = MultiTransactionInputImpl.newBuilder();
        oMultiTransactionInputImpl.setAddress(encApi.hexEnc(input.getAddress().toByteArray()));
        oMultiTransactionInputImpl.setAmount(ByteUtil.bytesToBigInteger(input.getAmount().toByteArray()).toString());
        for (ByteString cToken : input.getCryptoTokenList()) {
            oMultiTransactionInputImpl.addCryptoToken(encApi.hexEnc(cToken.toByteArray()));
        }
        oMultiTransactionInputImpl.setNonce(input.getNonce());
        oMultiTransactionInputImpl.setSymbol(input.getSymbol().toStringUtf8());
        oMultiTransactionInputImpl.setToken(input.getToken().toStringUtf8());
        oMultiTransactionInputImpl.setSliceId(input.getSliceId());
        bodyImpl.setInputs(oMultiTransactionInputImpl);

        for (TransactionOutput output : body.getOutputsList()) {
            MultiTransactionOutputImpl.Builder oMultiTransactionOutputImpl = MultiTransactionOutputImpl.newBuilder();
            oMultiTransactionOutputImpl.setAddress(encApi.hexEnc(output.getAddress().toByteArray()));
            oMultiTransactionOutputImpl
                    .setAmount(ByteUtil.bytesToBigInteger(output.getAmount().toByteArray()).toString());

            for (ByteString cToken : output.getCryptoTokenList()) {
                oMultiTransactionOutputImpl.addCryptoToken(encApi.hexEnc(cToken.toByteArray()));
            }
            // oMultiTransactionOutputImpl.setSymbol(output.getSymbol());
            oMultiTransactionOutputImpl.setSliceId(output.getSliceId());
            bodyImpl.addOutputs(oMultiTransactionOutputImpl);
        }

        MultiTransactionSignatureImpl.Builder signatureImpl = MultiTransactionSignatureImpl.newBuilder();
        signatureImpl.setSignature(encApi.hexEnc(body.getSignatures().toByteArray()));
        bodyImpl.addSignatures(signatureImpl);

        bodyImpl.setTimestamp(body.getTimestamp());
        txImpl.setTxBody(bodyImpl);

        MultiTransactionNodeImpl.Builder nodeImpl = MultiTransactionNodeImpl.newBuilder();
        txImpl.setNode(nodeImpl);
        return txImpl;
    }

    /**
     * List<ByteString> -> "token1, token2, token3"
     *
     * @param tokens List<ByteString>
     * @return "token1, token2, token3"
     */
    private String toTokens(List<ByteString> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return "";
        }

        return tokens.stream().map(t -> byte2String(t)).collect(Collectors.joining(","));
    }

    /**
     * "token1, token2, token3" -> List<ByteString>
     *
     * @param tokens "token1, token2, token3"
     * @return List<ByteString>
     */
    private List<ByteString> fromTokens(String tokens) {
        if (StringUtils.isBlank(tokens)) {
            return Lists.newArrayList();
        }

        return Arrays.stream(StringUtils.split(tokens, ",")).map(t -> ByteString.copyFrom(encApi.hexDec(t)))
                .collect(Collectors.toList());
    }

    @Override
    public Transaction.Builder parse(MultiTransactionImpl txImpl) throws BlockException {
        MultiTransactionBodyImpl bodyImpl = txImpl.getTxBody();

        Transaction.Builder tx = Transaction.newBuilder();
        tx.setHash(ByteString.copyFrom(encApi.hexDec(txImpl.getTxHash())));

        TransactionBody.Builder body = TransactionBody.newBuilder();
        body.setType(bodyImpl.getType());
        body.setData(ByteString.copyFrom(encApi.hexDec(bodyImpl.getData())));
        body.setExtData(ByteString.copyFrom(encApi.hexDec(bodyImpl.getExdata())));

        MultiTransactionInputImpl inputImpl = bodyImpl.getInputs();
        TransactionInput.Builder input = TransactionInput.newBuilder();
        input.setAddress(ByteString.copyFrom(encApi.hexDec(inputImpl.getAddress())));
        if (new BigInteger(inputImpl.getAmount()).compareTo(BigInteger.ZERO) < 0) {
            throw new TransactionException("amount must large than 0");
        }
        input.setAmount(ByteString.copyFrom(ByteUtil.bigIntegerToBytes(new BigInteger(inputImpl.getAmount()))));
        for (String cToken : inputImpl.getCryptoTokenList()) {
            input.addCryptoToken(ByteString.copyFrom(encApi.hexDec(cToken)));
        }
        input.setNonce(inputImpl.getNonce());
        input.setSymbol(ByteString.copyFromUtf8(inputImpl.getSymbol()));
        input.setToken(ByteString.copyFromUtf8(inputImpl.getToken()));

        // txImpl.getTxBody().getinpu

        body.setInput(input);

        for (MultiTransactionOutputImpl outputImpl : bodyImpl.getOutputsList()) {
            TransactionOutput.Builder output = TransactionOutput.newBuilder();
            output.setAddress(ByteString.copyFrom(encApi.hexDec(outputImpl.getAddress())));
            if (StringUtils.isNotBlank(outputImpl.getAmount())) {
                if (new BigInteger(outputImpl.getAmount()).compareTo(BigInteger.ZERO) < 0) {
                    throw new TransactionException("amount must large than 0");
                }
                output.setAmount(
                        ByteString.copyFrom(ByteUtil.bigIntegerToBytes(new BigInteger(outputImpl.getAmount()))));

                for (String cToken : outputImpl.getCryptoTokenList()) {
                    output.addCryptoToken(ByteString.copyFrom(encApi.hexDec(cToken)));
                }
            } else {
                output.setAmount(ByteString.copyFrom(ByteUtil.intToBytes(0)));
            }
            body.addOutputs(output);
        }

        body.setSignatures(ByteString.copyFrom(encApi.hexDec(bodyImpl.getSignatures(0).getSignature())));
        body.setTimestamp(bodyImpl.getTimestamp());
        tx.setBody(body);
        return tx;
    }

    /**
     * 校验并保存交易。该方法不会执行交易，用户的账户余额不会发生变化。
     *
     * @param tx
     * @throws BlockException
     */
    @Override
    public HashPair verifyAndSaveMultiTransaction(Transaction.Builder tx) throws BlockException {
        Map<ByteString, Account.Builder> accounts = getTransactionAccounts(tx);
        ITransactionActuator actuator = getActuator(tx.getBody().getType(), blockChainHelper.GetConnectBestBlock());

        // 如果交易本身需要验证签名
        if (actuator.needSignature()) {
            actuator.onVerifySignature(tx.build(), accounts);
        }

        // 执行交易执行前的数据校验
//		actuator.onPrepareExecute(tx.build(), accounts);

        tx.clearStatus();
        tx.clearHash();
        // 生成交易Hash
        tx.setHash(ByteString.copyFrom(encApi.sha256Encode(tx.getBody().toByteArray())));
        if (isExistsTransaction(tx.getHash())) {
            throw new BlockException("transaction exists, drop it txHash::" + byte2String(tx.getHash()));
        }

        // 保存交易到db中
        Transaction newTx = tx.build();
        HashPair ret = new HashPair(newTx.getHash(), newTx.toByteArray(), newTx);
        txStore.put(OEntityBuilder.byteKey2OKey(ret.getKeyBytes()), OEntityBuilder.byteValue2OValue(ret.getData()));
        return ret;
    }

    @Override
    public void resetActuator(ITransactionActuator ata, BlockEntity block) {
        ((AbstractTransactionActuator) ata).reset(this.oAccountHelper, this, block, encApi, this.stateTrie);
    }

    /**
     * @param transactionType 交易类型
     * @return 交易执行者
     */
    @Override
    public ITransactionActuator getActuator(int transactionType, BlockEntity block) {
        TransTypeEnum type = TransTypeEnum.trans(transactionType);
        return ActuatorFactory.get(type, this.oAccountHelper, this, block, encApi, this.stateTrie);
    }

    /**
     * @param tx
     */
    @Override
    public Map<ByteString, Account.Builder> getTransactionAccounts(Transaction.Builder tx) {
        Map<ByteString, Account.Builder> accounts = new HashMap<>();
        // 发起者
        TransactionInput input = tx.getBody().getInput();
        accounts.put(input.getAddress(), oAccountHelper.getAccountOrCreate(input.getAddress()));

        // 接收者
        tx.getBody().getOutputsList()
                .forEach(o -> accounts.put(o.getAddress(), oAccountHelper.getAccountOrCreate(o.getAddress())));

        ByteString lockAddress = ByteString.copyFrom(encApi.hexDec(BlockChainConfig.lock_account_address));
        if (!accounts.containsKey(lockAddress)) {
            accounts.put(lockAddress, oAccountHelper
                    .getAccountOrCreate(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.lock_account_address))));
        }

        ByteString cryptAddress = ByteString
                .copyFrom(encApi.hexDec(BlockChainConfig.cryptotoken_record_account_address));
        if (!accounts.containsKey(cryptAddress)) {
            accounts.put(cryptAddress, oAccountHelper.getAccountOrCreate(
                    ByteString.copyFrom(encApi.hexDec(BlockChainConfig.cryptotoken_record_account_address))));
        }

        ByteString tokenAddress = ByteString.copyFrom(encApi.hexDec(BlockChainConfig.token_record_account_address));
        if (!accounts.containsKey(tokenAddress)) {
            accounts.put(tokenAddress, oAccountHelper.getAccountOrCreate(
                    ByteString.copyFrom(encApi.hexDec(BlockChainConfig.token_record_account_address))));
        }

        ByteString sideChainAddress = ByteString
                .copyFrom(encApi.hexDec(BlockChainConfig.sidechain_record_account_address));
        if (!accounts.containsKey(sideChainAddress)) {
            accounts.put(sideChainAddress, oAccountHelper.getAccountOrCreate(
                    ByteString.copyFrom(encApi.hexDec(BlockChainConfig.sidechain_record_account_address))));
        }

        return accounts;
    }

    public Map<ByteString, Account.Builder> getTransactionAccounts(Transaction tx) {
        Map<ByteString, Account.Builder> accounts = new HashMap<>();
        // 发起者
        TransactionInput input = tx.getBody().getInput();
        accounts.put(input.getAddress(), oAccountHelper.getAccountOrCreate(input.getAddress()));

        tx.getBody().getOutputsList()
                .forEach(o -> accounts.put(o.getAddress(), oAccountHelper.getAccountOrCreate(o.getAddress())));

        ByteString lockAddress = ByteString.copyFrom(encApi.hexDec(BlockChainConfig.lock_account_address));
        if (!accounts.containsKey(lockAddress)) {
            accounts.put(lockAddress, oAccountHelper
                    .getAccountOrCreate(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.lock_account_address))));
        }

        ByteString cryptAddress = ByteString
                .copyFrom(encApi.hexDec(BlockChainConfig.cryptotoken_record_account_address));
        if (!accounts.containsKey(cryptAddress)) {
            accounts.put(cryptAddress, oAccountHelper.getAccountOrCreate(
                    ByteString.copyFrom(encApi.hexDec(BlockChainConfig.cryptotoken_record_account_address))));
        }

        ByteString tokenAddress = ByteString.copyFrom(encApi.hexDec(BlockChainConfig.token_record_account_address));
        if (!accounts.containsKey(tokenAddress)) {
            accounts.put(tokenAddress, oAccountHelper.getAccountOrCreate(
                    ByteString.copyFrom(encApi.hexDec(BlockChainConfig.token_record_account_address))));
        }

        ByteString sideChainAddress = ByteString
                .copyFrom(encApi.hexDec(BlockChainConfig.sidechain_record_account_address));
        if (!accounts.containsKey(sideChainAddress)) {
            accounts.put(sideChainAddress, oAccountHelper.getAccountOrCreate(
                    ByteString.copyFrom(encApi.hexDec(BlockChainConfig.sidechain_record_account_address))));
        }

        return accounts;
    }

    @Override
    public Map<ByteString, Account.Builder> merageTransactionAccounts(Transaction.Builder tx,
                                                                      Map<ByteString, Account.Builder> current) {
        return merageTransactionAccounts(tx.build(), current);
    }

    @Override
    public void merageSystemAccounts(Map<ByteString, Account.Builder> current) {
        if (!current.containsKey(BlockChainConfig.lock_account_address_bytestring)) {
            current.put(BlockChainConfig.lock_account_address_bytestring,
                    oAccountHelper.getAccountOrCreate(BlockChainConfig.lock_account_address_bytestring));
        }

        if (!current.containsKey(BlockChainConfig.cryptotoken_record_account_address_bytestring)) {
            current.put(BlockChainConfig.cryptotoken_record_account_address_bytestring,
                    oAccountHelper.getAccountOrCreate(BlockChainConfig.cryptotoken_record_account_address_bytestring));
        }

        if (!current.containsKey(BlockChainConfig.token_record_account_address_bytestring)) {
            current.put(BlockChainConfig.token_record_account_address_bytestring,
                    oAccountHelper.getAccountOrCreate(BlockChainConfig.token_record_account_address_bytestring));
        }

        if (!current.containsKey(BlockChainConfig.sidechain_record_account_address_bytestring)) {
            current.put(BlockChainConfig.sidechain_record_account_address_bytestring,
                    oAccountHelper.getAccountOrCreate(BlockChainConfig.sidechain_record_account_address_bytestring));
        }
    }

    /**
     * 在账户转换的时候, 验证分片是否在节点分片上面
     *
     * @param tx      交易
     * @param current 账户Map
     * @return Map
     */
    @Override
    public Map<ByteString, Account.Builder> merageTransactionAccounts(Transaction tx,
                                                                      Map<ByteString, Account.Builder> current) {
        TransactionInput input = tx.getBody().getInput();
        if (oAccountHelper.canExecute(input.getSliceId()) && !current.containsKey(input.getAddress())) {
            current.put(input.getAddress(), oAccountHelper.getAccountOrCreate(input.getAddress()));
        }

        tx.getBody().getOutputsList().forEach(o -> {
            if (oAccountHelper.canExecute(o.getSliceId()) && !current.containsKey(o.getAddress())) {
                current.put(o.getAddress(), oAccountHelper.getAccountOrCreate(o.getAddress()));
            }
        });

        return current;
    }

    @Override
    public byte[] getTransactionContent(Transaction tx) {
        Transaction.Builder newTx = Transaction.newBuilder();
        newTx.setBody(tx.getBody());
        newTx.setHash(tx.getHash());
        newTx.setNode(tx.getNode());
        return newTx.build().toByteArray();
    }

    @Override
    public void verifySignature(String pubKey, String signature, byte[] tx) throws BlockException {
        if (!isDisableEC && !encApi.ecVerify(pubKey, tx, encApi.hexDec(signature))) {
            throw new BlockException(String.format("签名 %s 使用公钥 %s 验证失败", pubKey, signature));
        }
    }

    ConcurrentLinkedQueue<OPair.Builder> txDeferCache = new ConcurrentLinkedQueue<>();
    // ConcurrentLinkedQueue<OValue> txDeferCacheValue = new
    // ConcurrentLinkedQueue<>();

    boolean txSaveDeferMode = true;

    @Override
    public void setTransactionDone(Transaction transaction, BlockEntity block, ByteString result)
            throws BlockException {

        Transaction.Builder tx = transaction.toBuilder();
        TXStatus.setDone(tx, block, result);
        txDBCacheByHash.put(tx.getHash(), tx.build());

        if (txSaveDeferMode) {
            OPair.Builder p = OPair.newBuilder().setKey(OEntityBuilder.byteKey2OKey(tx.getHash()))
                    .setValue(OEntityBuilder.byteValue2OValue(tx.build().toByteArray()));
            txDeferCache.add(p);
        } else {
            txStore.put(OEntityBuilder.byteKey2OKey(tx.getHash()),
                    OEntityBuilder.byteValue2OValue(tx.build().toByteArray()));
        }
        /*
         * txStore.put(OEntityBuilder.byteKey2OKey(tx.getHash()),
         * OEntityBuilder.byteValue2OValue(tx.build().toByteArray(),
         * String.valueOf(block.getHeader().getNumber())));
         */

    }

    @Override
    public void setTxSaveDeferMode(boolean defermode) {
        txSaveDeferMode = defermode;
    }

    @Override
    public boolean isTxSaveDeferMode() {
        return txSaveDeferMode;
    }
//	AtomicBoolean lock = new AtomicBoolean();

    @Override
    public synchronized void flushDeferCache() {
//		while(!lock.compareAndSet(false, true));
//		new Thread(new Runnable() {

//			@Override
//			public void run() {
        // TODO Auto-generated method stub
        try {
            int size = txDeferCache.size();
            if (size <= 0)
                return;
            OKey keys[] = new OKey[size];
            OValue values[] = new OValue[size];
            int i = 0;
            for (OPair.Builder op : txDeferCache) {
                keys[i] = op.getKey();
                values[i] = op.getValue();
                i++;
            }
            txStore.batchPuts(keys, values);
            txDeferCache.clear();
        } finally {
//					lock.set(false);
        }
//			}
//		}).start();

    }

    @Override
    public void setTransactionError(Transaction transaction, BlockEntity block, ByteString result)
            throws BlockException {
        Transaction.Builder tx = transaction.toBuilder();
        TXStatus.setError(tx, block, result);

        txDBCacheByHash.put(tx.getHash(), tx.build());

        if (txSaveDeferMode) {
            OPair.Builder p = OPair.newBuilder().setKey(OEntityBuilder.byteKey2OKey(tx.getHash()))
                    .setValue(OEntityBuilder.byteValue2OValue(tx.build().toByteArray()));
            txDeferCache.add(p);
        } else {
            txStore.put(OEntityBuilder.byteKey2OKey(tx.getHash()),
                    OEntityBuilder.byteValue2OValue(tx.build().toByteArray()));
        }
        /*
         * txStore.put(OEntityBuilder.byteKey2OKey(oTransaction.getHash()),
         * OEntityBuilder .byteValue2OValue(oTransaction.build().toByteArray(),
         * String.valueOf(block.getHeader().getNumber())));
         */
    }

    /**
     * generate contract address by transaction
     *
     * @param tx
     * @return
     */
    @Override
    public ByteString getContractAddressByTransaction(Transaction tx) throws BlockException {
        if (tx.getBody().getOutputsCount() != 0 || tx.getBody().getInput() == null) {
            throw new BlockException("transaction type is wrong.");
        }

        byte[] address = encApi.sha3Encode(RLP.encodeList(tx.getBody().getInput().getAddress().toByteArray(),
                ByteUtil.intToBytes(tx.getBody().getInput().getNonce())));
        return ByteString.copyFrom(copyOfRange(address, 12, address.length));
    }

    @Override
    public boolean isExistsTransaction(ByteString hash) {
        try {
            OValue value = txStore.get(OEntityBuilder.byteKey2OKey(hash)).get();
            if (log.isDebugEnabled()) {
                log.debug("===TxHash: {}, value: {}", encApi.hexEnc(hash.toByteArray()), value);
            }
            return !(value == null || value.getExtdata() == null);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long getConfirmSize() {
        return oConfirmMapDB.size();
    }

    /*
     * block经过见证人共识后的通知。 账户层面会将相同高度的但是hash不一致的区块断开连接，但不会从db中删除。
     *
     * @see org.csc.account.api.ITransactionHelper#confirmBlock(java.lang.String)
     */
    @Override
    public void confirmBlock(String blockHash) {
        blockChainHelper.confirmBlock(blockHash);
    }

    @Override
    public List<ByteString> getRelationAccount(Transaction tx) {
//		while(lock.get());
        List<ByteString> list = new ArrayList<>();
        for (Tx.TransactionOutput output : tx.getBody().getOutputsList()) {
            list.add(output.getAddress());
        }
        list.add(tx.getBody().getInput().getAddress());
        if (tx.getBody().getType() == TransTypeEnum.TYPE_CreateCryptoToken.value()) {
            list.add(BlockChainConfig.lock_account_address_bytestring);
            list.add(BlockChainConfig.cryptotoken_record_account_address_bytestring);
        } else if (tx.getBody().getType() == TransTypeEnum.TYPE_CreateToken.value()) {
            list.add(BlockChainConfig.lock_account_address_bytestring);
            list.add(BlockChainConfig.token_record_account_address_bytestring);
        } else if (tx.getBody().getType() == TransTypeEnum.TYPE_BurnToken.value()
                || tx.getBody().getType() == TransTypeEnum.TYPE_MintToken.value()) {
            list.add(BlockChainConfig.token_record_account_address_bytestring);
        } else if (tx.getBody().getType() == TransTypeEnum.TYPE_CreateContract.value()
                || tx.getBody().getType() == TransTypeEnum.TYPE_CreateUnionAccount.value()) {
            list.add(BlockChainConfig.lock_account_address_bytestring);
        } else if (tx.getBody().getType() == TransTypeEnum.TYPE_CryptoTokenTransaction.value()) {
            // 相同的symbol不会被拆分
            list.add(tx.getBody().getInput().getSymbol());
        }
        // else if (tx.getBody().getType() == TransTypeEnum.TYPE_DOWN_TRANS.value()
        // || tx.getBody().getType() == TransTypeEnum.TYPE_UP_TRANS.value()) {
        // list.add(BlockChainConfig.offline_address_bytestring);
        // }
        else if (tx.getBody().getType() == TransTypeEnum.TYPE_sideChainSync.value()
                || tx.getBody().getType() == TransTypeEnum.TYPE_sideChainReg.value()) {
            list.add(BlockChainConfig.sidechain_record_account_address_bytestring);
        }
        return list;
    }
}
