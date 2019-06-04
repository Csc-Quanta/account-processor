package org.csc.account.core.processor;

import com.google.protobuf.ByteString;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.csc.account.api.ITransactionActuator;
import org.csc.account.core.processor.TxRunnerInfo.IndexTransaction;
import org.csc.account.processor.BlockChainConfig;
import org.csc.evmapi.gens.Block;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 处理执行交易, 并设置执行结果
 * 
 * @author lance
 * @since 2019.1.17 09:48
 */
@Slf4j
@AllArgsConstructor
public class MisV2TransactionRunner implements Runnable {
	private TxRunnerInfo info;

	@Override
	public void run() {
		Map<Integer, ITransactionActuator> actorByType = new HashMap<>();
		BlockEntity block = info.getBlock();

		while (info.getCdl().getCount() > 0) {
			IndexTransaction itx = info.getQueue().poll();
			
			if (itx == null) {
				continue;
			}
			Tx.Transaction tx = itx.getTx();
			ITransactionActuator actuator = actorByType.get(tx.getBody().getType());
			if (actuator == null) {
				actuator = info.getTransaction().getActuator(tx.getBody().getType(), block);
				actorByType.put(tx.getBody().getType(), actuator);
			} else {
				info.getTransaction().resetActuator(actuator, block);
			}

			try {
				actuator.onPrepareExecute(tx, info.getAccounts());
				ByteString result = actuator.onExecute(tx, info.getAccounts());
				actuator.onExecuteDone(tx, block, result);

				// 如果Tx执行结果不为Empty.
				if (!result.isEmpty()) {
					info.getRes().offer(success(tx.getHash(), result,itx.getIndex()));
				}
			} catch (Throwable e) {
				log.error("block: {}, height: {}, exec transaction hash::{}, Error: {}",
						BlockChainConfig.convert(block.getHeader().getHash()), block.getHeader().getNumber(),
						BlockChainConfig.convert(tx.getHash()), e.getMessage());
				try {
					ByteString result = ByteString
							.copyFromUtf8(e.getMessage() == null ? "unknown exception" : e.getMessage());
					actuator.onExecuteError(tx, block, result);
					info.getRes().offer(fail(tx.getHash(), result,itx.getIndex()));
				} catch (Exception e1) {
					log.error("onexec errro:" + e1.getMessage(), e1);
				}
			} finally {
				info.getCdl().countDown();
			}
		}
	}

	/**
	 * 创建执行失败结果
	 * 
	 * @param hash TxHash
	 * @param res  Tx执行结果
	 * @return BlockTransactionResult
	 */
	private Block.BlockTransactionResult fail(ByteString hash, ByteString res,int index) {
		return create(hash, "E", res,index);
	}

	/**
	 * 创建执行成功结果
	 * 
	 * @param hash TxHash
	 * @param res  Tx执行结果
	 * @return BlockTransactionResult
	 */
	private Block.BlockTransactionResult success(ByteString hash, ByteString res,int index) {
		return create(hash, "D", res,index);
	}

	/**
	 * 创建执行结果
	 * 
	 * @param hash   TxHash
	 * @param status Tx.status
	 * @param res    Tx执行结果
	 * @return BlockTransactionResult
	 */
	private Block.BlockTransactionResult create(ByteString hash, String status, ByteString res,int index) {
		return Block.BlockTransactionResult.newBuilder().setI(index).setResult(res.toStringUtf8())
				.setStatus(status).build();
	}

	/**
	 * 获取当前交易在header[txs]中索引
	 * 
	 * @param hash 交易Tx.hash
	 * @param list 所有Tx.hash列表
	 * @return 索引
	 */
	private int index(ByteString hash, List<ByteString> list) {
		return list.indexOf(hash);
	}
}
