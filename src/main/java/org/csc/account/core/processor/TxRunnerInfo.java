package org.csc.account.core.processor;

import com.google.protobuf.ByteString;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.csc.account.api.ITransactionHelper;
import org.csc.evmapi.gens.Act;
import org.csc.evmapi.gens.Block;
import org.csc.evmapi.gens.Block.BlockTransactionResult;
import org.csc.evmapi.gens.Tx;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 互不干扰对象之间传递对象
 * @author lance
 * @since 2019.1.16 17:41
 */
@Data
@Builder
public class TxRunnerInfo {
	
	@AllArgsConstructor
	@Data
	public static class IndexTransaction{
		Tx.Transaction tx;
		List<BlockTransactionResult> results;
		int	index;
	}
    /**分离处理Tx的Queue*/
    private LinkedBlockingQueue<IndexTransaction> queue;
    /**处理Tx*/
    private ITransactionHelper transaction;
    /**创建Block, 同步Block*/
    private Block.BlockEntity block;
    /**当前Block所有参与者账户*/
    private Map<ByteString, Act.Account.Builder> accounts;
    /**执行失败结果集或者成功Result不为空的(目前主要是Contract)*/
    private LinkedBlockingQueue<Block.BlockTransactionResult> res;
    /**CountDownLatch*/
    private CountDownLatch cdl;

//    /**Block.header中Txs集合*/
//    protected List<ByteString> txs(){
//        if(block != null && block.getHeader() != null){
//            return block.getHeader().getTxHashsList();
//        }
//
//        return null;
//    }
}
