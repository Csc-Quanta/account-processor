package org.csc.account.core.processor;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.csc.account.api.ITransactionActuator;
import org.csc.account.core.processor.TxRunnerInfo.IndexTransaction;
import org.csc.account.processor.BlockChainConfig;
import org.csc.evmapi.gens.Block;
import org.csc.evmapi.gens.Tx;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 抽象交易执行器
 * @author lance
 * 4/12/2019 17:18
 */
@Slf4j
public abstract class AbstractTransactionRunner implements Callable<List<Block.BlockTransactionResult>> {
    protected TxRunnerInfo info;
    /**
     * 创建执行失败结果
     *
     * @param hash TxHash
     * @param res  Tx执行结果
     * @return BlockTransactionResult
     */
    protected Block.BlockTransactionResult fail(ByteString hash, ByteString res,int index) {
        return create(hash, "E", res,index);
    }

    /**
     * 创建执行成功结果
     *
     * @param hash TxHash
     * @param res  Tx执行结果
     * @return BlockTransactionResult
     */
    Block.BlockTransactionResult success(ByteString hash, ByteString res,int index) {
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
    protected Block.BlockTransactionResult create(ByteString hash, String status, ByteString res,int index) {
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
    protected int index(ByteString hash, List<ByteString> list) {
        return list.indexOf(hash);
    }

    /**
     * 交易执行失败
     */
    void executeError(Tx.Transaction tx, Block.BlockEntity block, ITransactionActuator actuator,
    		IndexTransaction itx, Throwable e,int index){
//        log.error("block: {}, height: {}, exec transaction hash::{}, Error: {}",
//                BlockChainConfig.convert(block.getHeader().getHash()), block.getHeader().getNumber(),
//                BlockChainConfig.convert(tx.getHash()), e.getMessage());
        try {
            if(log.isDebugEnabled()){
                log.debug("Transaction execute error::", e);
            }

            ByteString result = ByteString.copyFromUtf8(e.getMessage() == null ? "unknown exception" : e.getMessage());
            actuator.onExecuteError(tx, block, result);
//            itx.setResult(fail(tx.getHash(), result,index));
            itx.results.set(itx.getIndex(),fail(tx.getHash(), result,index));
        } catch (Exception e1) {
            log.error("onexec errro:" + e1.getMessage(), e1);
        }
    }
    
    
}
