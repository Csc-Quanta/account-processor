package org.csc.account.core.processor;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.csc.account.api.ITransactionActuator;
import org.csc.account.core.processor.TxRunnerInfo.IndexTransaction;
import org.csc.evmapi.gens.Block;
import org.csc.evmapi.gens.Tx;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 特殊交易执行器
 *
 * @author lance 4/12/2019 19:47
 */
@Slf4j
public class MisV3SyncRunner extends AbstractTransactionRunner {
	private List<Block.BlockTransactionResult> results = Lists.newArrayList();
	private int count = 0;

	MisV3SyncRunner(TxRunnerInfo info, int count) {
		this.info = info;
		this.count = count;
	}

	@Override
	public List<Block.BlockTransactionResult> call() throws Exception {
		Map<Integer, ITransactionActuator> actorByType = Maps.newHashMap();
		Block.BlockEntity block = info.getBlock();

		while (count-- > 0) {
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
					itx.results.set(itx.getIndex(),(success(tx.getHash(), result, itx.getIndex())));
				}
			} catch (Throwable e) {
				executeError(tx, block, actuator, itx, e, itx.getIndex());
			}
		}

		actorByType.clear();
		return results;
	}
}
