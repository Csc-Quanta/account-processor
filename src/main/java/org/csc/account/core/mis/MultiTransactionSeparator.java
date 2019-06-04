package org.csc.account.core.mis;

import com.google.protobuf.ByteString;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import onight.tfw.outils.conf.PropHelper;

import org.csc.account.api.IAccountOperateListener;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.core.processor.V4Processor;
import org.csc.account.core.processor.TxRunnerInfo.IndexTransaction;
import org.csc.bcutil.Hex;
import org.csc.evmapi.gens.Tx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutual Irrelevance Separator Filter
 * 
 * 互不相关性分离器
 * 
 * @author brew
 *
 */

@NoArgsConstructor
@AllArgsConstructor
@Data
@Slf4j
public class MultiTransactionSeparator {

	static PropHelper props = new PropHelper(null);
	public static int bucketSize = props.get("org.csc.account.mis.parral",
			Runtime.getRuntime().availableProcessors() * 2);
	public static int fastaddrbits = props.get("org.csc.account.mis.fastaddrbits", 2);

	private static ExecutorService executor = new ForkJoinPool(bucketSize);

	class RelationShip implements Runnable {
		Map<String, IndexTransaction> sequances = new HashMap<>();

		LinkedBlockingQueue<IndexTransaction> queue = new LinkedBlockingQueue<>();
		List<String> accounts;

		int bucketIdx = -1;
		CountDownLatch cdl;

		void reset(CountDownLatch cdl, int bucketIdx, List<String> accounts) {
			this.accounts = accounts;
			this.bucketIdx = bucketIdx;
			this.cdl = cdl;
		}

		@Override
		public void run() {
			try {
				if (accounts != null) {
					for (String address : accounts) {
						if (sequances.containsKey(address)) {
							txBuckID.compareAndSet(-1, bucketIdx);
							txSplitCounter.incrementAndGet();
							break;
						}
					}
				}
			} finally {
				cdl.countDown();
			}
		}
	}

	public LinkedBlockingQueue<IndexTransaction> getTxnQueue(int index) {
		return buckets.get(index).queue;
	}

	private RelationShip syncRelationShip = new RelationShip();

	public LinkedBlockingQueue<IndexTransaction> getSyncTxnQueue() {
		return syncRelationShip.queue;
	}

	public void reset() {
		if (buckets != null) {
			buckets.clear();
		} else {
			buckets = new ArrayList<>(bucketSize);
		}
		syncRelationShip = new RelationShip();
		// .sequances.clear();
		// syncRelationShip.queue.clear();

		offset = 0;

		for (int i = 0; i < bucketSize; i++) {
			buckets.add(new RelationShip());
		}
	}

	List<RelationShip> buckets = new ArrayList<>();

	public String getBucketInfo() {
		StringBuffer sb = new StringBuffer("MIS.MTS,BucketSize=").append(buckets.size()).append(":[");
		for (int i = 0; i < bucketSize; i++) {
			RelationShip rs = buckets.get(i);
			if (i > 0) {
				sb.append(",");
			}
			sb.append(rs.sequances.size());
		}
		sb.append("]");
		return sb.toString();
	}

	public static String fastAddress(ByteString address) {
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < fastaddrbits && i < address.size(); i++) {
			sb.append(address.byteAt(i));
		}
		return sb.toString();
	}

	public static List<String> toFastAddress(List<ByteString> relationAccount) {
		List<String> fastAddressAccount = new ArrayList<>();
		for (ByteString address : relationAccount) {
			fastAddressAccount.add(fastAddress(address));
		}
		return fastAddressAccount;
	}

	int offset = 0;
	int sepCount = 0;
	int[] sepLog = new int[bucketSize];

	public void resetBuffer() {
		sepCount = 0;
		sepLog = new int[bucketSize];
		fastAddrByTxid.clear();
	}

	public void doClearing(IndexTransaction[] oMultiTransactions, ITransactionHelper transactionHelper,
			IAccountOperateListener loadTester, long height) {
		sepCount++;
		offset = 0;
		for (IndexTransaction tx : oMultiTransactions) {
			doSetTX(tx, transactionHelper, loadTester, height);
		}
	}

	public void doClearing(LinkedBlockingQueue<IndexTransaction> oMultiTransactions, ITransactionHelper transactionHelper,
			IAccountOperateListener loadTester, long height,CountDownLatch cdl) {
		offset = 0;
		sepCount++;
		for (IndexTransaction tx : oMultiTransactions) {
			try {
				doSetTX(tx, transactionHelper, loadTester, height);
			} catch (Exception e) {
				cdl.countDown();
				log.error("error in running tx:"+tx.getTx()+","+tx.getResults()+","+tx.getIndex(),e);
			}
		}
	}

	public void outputLog() {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < sepLog.length; i++) {
			if (i > 0) {
				sb.append(",");
			}
			sb.append(sepLog[i]);
		}
		sb.append("]");
		log.error("septx=" + sb.toString() + ",sepcc=" + sepCount + ",sync=" + syncRelationShip.queue.size());
	}

	Map<ByteString, List<String>> fastAddrByTxid = new ConcurrentHashMap<>();

	AtomicInteger txSplitCounter = new AtomicInteger(0);
	AtomicInteger txBuckID = new AtomicInteger(-1);

	public void doSetTX(IndexTransaction tx, ITransactionHelper transactionHelper, IAccountOperateListener loadTester,
			long height) {

		int splitCount = 0;

		List<String> fastAddressAccount = fastAddrByTxid.get(tx.getTx().getHash());
		if (fastAddressAccount == null) {
			List<ByteString> _relationAccount = transactionHelper.getRelationAccount(tx.getTx());
			fastAddressAccount = toFastAddress(_relationAccount);
			fastAddrByTxid.put(tx.getTx().getHash(), fastAddressAccount);
		}
		/**
		 * <p>
		 * A -> B ==> bucket(0)
		 * <p>
		 * c -> D ==> bucket(1)
		 * <p>
		 * B -> C ==> sync
		 * <p>
		 * D -> C ==> sync
		 * <p>
		 * E -> F ==> bucket(2)
		 * <p>
		 * C -> G ==> sync
		 * <p>
		 * M -> N (x) ==> bucket(3)
		 * <p>
		 * P -> Q (x) ==> bucket(3)
		 * <p>
		 * R -> s (x,y) ==> bucket(3)
		 */
//		txSplitCounter.set(0);
//		txBuckID.set(-1);
		int bucketIdx = -1;
		boolean isInSync = false;
		for (String address : fastAddressAccount) {
			if (syncRelationShip.sequances.containsKey(address)) {
				isInSync = true;
				break;
			}
		}
		if (!isInSync) {
			// CountDownLatch cdl = new CountDownLatch(bucketSize);
			for (int i = 0; i < bucketSize; i++) {
				RelationShip rs = buckets.get(i);
				// rs.reset(cdl,i, fastAddressAccount);
				// executor.submit(rs);
				for (String address : fastAddressAccount) {
					if (rs.sequances.containsKey(address)) {
						bucketIdx = i;
						splitCount++;
						break;
					}
				}
			}
			// try {
			// cdl.await();
			// } catch (InterruptedException e) {
			// // TODO Auto-generated catch block
			// e.printStackTrace();
			// }
		}
		if (isInSync || splitCount > 1) {
			for (String address : fastAddressAccount) {
				syncRelationShip.sequances.put(address, tx);
			}
			syncRelationShip.queue.offer(tx);
		} else {
			if (bucketIdx < 0) {
				bucketIdx = offset % bucketSize;
				offset++;
			}
			RelationShip rs = buckets.get(bucketIdx);

			sepLog[bucketIdx] = sepLog[bucketIdx] + 1;

			for (String address : fastAddressAccount) {
				rs.sequances.put(address, tx);
			}
			if (loadTester != null) {
				Tx.TransactionInput inputTest = tx.getTx().getBody().getInput();
				loadTester.offerNewAccount(inputTest.getAddress(), inputTest.getNonce());
			}
			rs.queue.offer(tx);
		}
	}
}
