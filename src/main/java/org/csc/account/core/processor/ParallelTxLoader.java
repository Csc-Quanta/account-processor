package org.csc.account.core.processor;

import com.google.protobuf.ByteString;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.bean.HashPair;
import org.csc.account.core.mis.MultiTransactionSeparator;
import org.csc.bcutil.Hex;
import org.csc.evmapi.gens.Act;
import org.csc.evmapi.gens.Tx;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 加载本地交易
 * @author lance
 * 4/16/2019 14:39
 */
@Slf4j
@AllArgsConstructor
public class ParallelTxLoader implements Callable<String> {
    private ByteString txHash;
    private int index;
    private Tx.Transaction[] bb;
    private byte[][] txTrieBB;
    private Map<ByteString, Act.Account.Builder> accounts;
    private long number;
    private AtomicBoolean justCheck;
    private ITransactionHelper transactionHelper;
    private Map<ByteString,List<String>>  fastAddrByTxid;
    ConcurrentLinkedQueue<String> lackHash;
    CountDownLatch cdl;
    @Override
    public String call() throws Exception {
        try {
            Thread.currentThread().setName("txLoader-" + number);
            HashPair hp = transactionHelper.removeWaitingSendOrBlockTx(txHash);
            Tx.Transaction tx = null;
            if (hp != null) {
                tx = hp.getTx();
            }

            tx = tx == null ? transactionHelper.GetTransaction(txHash) : tx;
            if (tx == null || tx.getHash().isEmpty() || tx.getBody().getInput() == null) {
                justCheck.set(true);
                String txHex = Hex.toHexString(txHash.toByteArray());
                lackHash.add(txHex);
                return txHex;
            } else {
                if (!justCheck.get()) {
                    bb[index] = tx;
                    txTrieBB[index] = transactionHelper.getTransactionContent(tx);
                    transactionHelper.merageTransactionAccounts(tx, accounts);
                    List<String> fastAddressAccount = fastAddrByTxid.get(tx.getHash());
            		if(fastAddressAccount==null)
            		{
            			List<ByteString> _relationAccount = transactionHelper.getRelationAccount(tx);
            			fastAddressAccount= MultiTransactionSeparator.toFastAddress(_relationAccount);
            			fastAddrByTxid.put(tx.getHash(),fastAddressAccount);
            		}
                }
            }
        } catch (Exception e) {
            log.error("error in loading tx: {}, idx={}", Hex.toHexString(txHash.toByteArray()), index, e);
        } finally {
        	cdl.countDown();
            Thread.currentThread().setName("stateTrie-pool");
        }
        return null;
    }
}
