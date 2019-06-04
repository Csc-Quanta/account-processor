package org.csc.account.core.actuator;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.csc.account.api.IAccountHelper;
import org.csc.account.api.IStateTrie;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.TransactionParameterInvalidException;
import org.csc.account.processor.BlockChainConfig;
import org.csc.account.util.ByteUtil;
import org.csc.account.util.FastByteComparisons;
import org.csc.bcapi.EncAPI;
import org.csc.evmapi.gens.Act.Account;
import org.csc.evmapi.gens.Act.AccountValue;
import org.csc.evmapi.gens.Act.SanctionStorage;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx;
import org.csc.evmapi.gens.Tx.SanctionData;

import java.util.Map;

/**
 * 仲裁
 * 
 * @author brew
 *
 */
@Slf4j
public class ActuatorSanctionTransaction extends AbstractTransactionActuator {

	public ActuatorSanctionTransaction(IAccountHelper oAccountHelper, ITransactionHelper oTransactionHelper,
			BlockEntity oBlock, EncAPI encApi, IStateTrie oStateTrie) {
		super(oAccountHelper, oTransactionHelper, oBlock, encApi, oStateTrie);
	}

	@Override
	public void onPrepareExecute(Tx.Transaction oMultiTransaction, Map<ByteString, Account.Builder> accounts)
			throws BlockException {
		if (oMultiTransaction.getBody().getInput() == null
				|| oMultiTransaction.getBody().getOutputsCount() != 1) {
			throw new TransactionParameterInvalidException("parameter invalid, inputs or outputs must be only one");
		}

		SanctionData.Builder oSanctionData;
		try {
			oSanctionData = SanctionData.parseFrom(oMultiTransaction.getBody().getData().toByteArray()).toBuilder();
		} catch (Exception e) {
			throw new TransactionParameterInvalidException("parameter invalid, sanction data format invalid");
		}
		if (oBlock != null && oBlock.getHeader().getNumber() > oSanctionData.getEndBlockNumber()) {
			throw new TransactionParameterInvalidException("parameter invalid, this vote has ended");
		}

		Tx.TransactionInput oInput = oMultiTransaction.getBody().getInput();
		Account.Builder senderAccount = accounts.get(oInput.getAddress());

		ByteString contractAddr = oMultiTransaction.getBody().getOutputs(0).getAddress();
		Account.Builder contractAccount = accounts.get(contractAddr);
		AccountValue.Builder oContractAccountValue = contractAccount.getValue().toBuilder();

		byte[] voteKey = ByteUtil.intToBytes(oContractAccountValue.getNonce());

		byte[]votehashBB = oAccountHelper.getStorage(contractAccount, voteKey);
		if (votehashBB == null) {
			if (ByteUtil.bytesToBigInteger(oInput.getAmount().toByteArray())
					.compareTo(BlockChainConfig.minSanctionCost) < 0) {
				throw new TransactionParameterInvalidException("parameter invalid, not enouth CWS token cost");
			}

			if (oAccountHelper.getTokenBalance(senderAccount, ByteString.copyFromUtf8("CWS"))
					.compareTo(ByteUtil.bytesToBigInteger(oInput.getAmount().toByteArray())) < 0) {
				throw new TransactionParameterInvalidException(
						"parameter invalid, not enouth CWS token to initiate vote");
			}
		} else {
			if (ByteUtil.bytesToBigInteger(oInput.getAmount().toByteArray())
					.compareTo(BlockChainConfig.minVoteCost) < 0) {
				throw new TransactionParameterInvalidException("parameter invalid, not enouth CWS token cost");
			}

			try {
				SanctionStorage oSanctionStorage = SanctionStorage.parseFrom(votehashBB);

				for (ByteString b : oSanctionStorage.getAddressList()) {
					if (FastByteComparisons.equal(b.toByteArray(), oInput.getAddress().toByteArray())) {
						throw new TransactionParameterInvalidException(
								"parameter invalid, Duplicate join vote is not allowed");
					}
				}

				if (oAccountHelper.getTokenBalance(senderAccount, ByteString.copyFromUtf8("CWS"))
						.compareTo(ByteUtil.bytesToBigInteger(oInput.getAmount().toByteArray())) < 0) {
					throw new TransactionParameterInvalidException(
							"parameter invalid, not enouth CWS token to join vote");
				}

				Tx.Transaction oVoteTx = oTransactionHelper.GetTransaction(oSanctionStorage.getVoteTxHash());
				if (oVoteTx == null) {
					throw new TransactionParameterInvalidException("parameter invalid, not found vote transaction");
				}

				SanctionData.Builder oVoteSanctionData = SanctionData
						.parseFrom(oVoteTx.getBody().getData().toByteArray()).toBuilder();

				if (oSanctionData.getEndBlockNumber() != oVoteSanctionData.getEndBlockNumber()
						|| !FastByteComparisons.equal(oVoteSanctionData.getContent().toByteArray(),
								oSanctionData.getContent().toByteArray())) {
					throw new TransactionParameterInvalidException("parameter invalid, vote content invalidate");
				}
			} catch (InvalidProtocolBufferException e) {
				throw new BlockException(e);
			}
		}
	}

	@Override
	public ByteString onExecute(Tx.Transaction tx, Map<ByteString, Account.Builder> accounts)
			throws BlockException {
		Tx.TransactionInput oInput = tx.getBody().getInput();
		Account.Builder senderAccount = accounts.get(oInput.getAddress());
		AccountValue.Builder senderAccountValue = senderAccount.getValue().toBuilder();

		ByteString contractAddr = tx.getBody().getOutputs(0).getAddress();
		Account.Builder contractAccount = accounts.get(contractAddr);
		AccountValue.Builder oContractAccountValue = contractAccount.getValue().toBuilder();

		byte[] voteKey = ByteUtil.intToBytes(oContractAccountValue.getNonce());

		byte votehashBB[] = oAccountHelper.getStorage(contractAccount, voteKey);
		SanctionStorage.Builder oSanctionStorage = SanctionStorage.newBuilder();
		if (votehashBB == null) {
			oAccountHelper.subTokenBalance(senderAccount, ByteString.copyFromUtf8("CWS"),
					ByteUtil.bytesToBigInteger(oInput.getAmount().toByteArray()));
			oAccountHelper.addTokenBalance(contractAccount, ByteString.copyFromUtf8("CWS"),
					ByteUtil.bytesToBigInteger(oInput.getAmount().toByteArray()));
			oSanctionStorage.setVoteTxHash(tx.getHash());
		} else {
			oAccountHelper.subTokenBalance(senderAccount, ByteString.copyFromUtf8("CWS"),
					ByteUtil.bytesToBigInteger(oInput.getAmount().toByteArray()));
			oAccountHelper.addTokenBalance(contractAccount, ByteString.copyFromUtf8("CWS"),
					ByteUtil.bytesToBigInteger(oInput.getAmount().toByteArray()));
			try {
				oSanctionStorage = SanctionStorage.parseFrom(votehashBB).toBuilder();
			} catch (InvalidProtocolBufferException e) {
				throw new BlockException(e);
			}
		}
		oSanctionStorage.addAddress(oInput.getAddress());
		oSanctionStorage.addTxHash(tx.getHash());
		oAccountHelper.putStorage(contractAccount, voteKey, oSanctionStorage.build().toByteArray());
		accounts.put(contractAddr, contractAccount);

		senderAccountValue.setNonce(senderAccountValue.getNonce() + 1);
		senderAccount.setValue(senderAccountValue);
		accounts.put(oInput.getAddress(), senderAccount);
		return ByteString.EMPTY;
	}
}
