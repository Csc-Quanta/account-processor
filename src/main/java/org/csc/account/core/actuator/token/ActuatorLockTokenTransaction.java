package org.csc.account.core.actuator.token;

import com.google.protobuf.ByteString;
import org.csc.account.api.IAccountHelper;
import org.csc.account.api.IStateTrie;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.core.actuator.AbstractTransactionActuator;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.TransactionExecuteException;
import org.csc.account.exception.TransactionParameterInvalidException;
import org.csc.account.util.ByteUtil;
import org.csc.bcapi.EncAPI;
import org.csc.evmapi.gens.Act.Account;
import org.csc.evmapi.gens.Act.AccountTokenValue;
import org.csc.evmapi.gens.Act.AccountValue;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx.Transaction;
import org.csc.evmapi.gens.Tx.TransactionInput;

import java.math.BigInteger;
import java.util.Map;

/**
 * Token锁定
 * @author lance
 * @since 2019.1.9 20:40
 */
public class ActuatorLockTokenTransaction extends AbstractTransactionActuator {

	@Override
	public void onPrepareExecute(Transaction tx, Map<ByteString, Account.Builder> accounts)
			throws BlockException {
		if (tx.getBody().getInput() == null) {
			throw new TransactionParameterInvalidException("parameter invalid, inputs must be only one");
		}

		if (tx.getBody().getOutputsCount() != 0) {
			throw new TransactionParameterInvalidException("parameter invalid, outputs must be null");
		}

		TransactionInput oInput = tx.getBody().getInput();
		//不在同一个分片上面, 不需要执行
		if(oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(oInput.getSliceId())){
			return;
		}

		Account.Builder sender = accounts.get(oInput.getAddress());
		AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();
		BigInteger tokenBalance = BigInteger.ZERO;
		for (int i = 0; i < senderAccountValue.getTokensCount(); i++) {
			if (senderAccountValue.getTokens(i).getToken().equals(oInput.getToken())) {
				tokenBalance = ByteUtil.bytesAdd(tokenBalance,
						senderAccountValue.getTokens(i).getBalance().toByteArray());
				break;
			}
		}
		
		BigInteger bi = ByteUtil.bytesToBigInteger(oInput.getAmount().toByteArray());
		if (bi.compareTo(BigInteger.ZERO) < 0) {
			throw new TransactionParameterInvalidException("parameter invalid, amount must large than 0");
		}

		if (tokenBalance.compareTo(bi) >= 0) {
		} else {
			throw new TransactionParameterInvalidException(String.format("parameter invalid, sender balance %s less than %s", tokenBalance,
					ByteUtil.bytesToBigInteger(oInput.getAmount().toByteArray())));
		}

		int nonce = senderAccountValue.getNonce();
		if (nonce > oInput.getNonce()) {
			throw new TransactionParameterInvalidException(
					String.format("parameter invalid, sender nonce %s is not equal with transaction nonce %s", nonce, oInput.getNonce()));
		}
	}

	@Override
	public ByteString onExecute(Transaction tx, Map<ByteString, Account.Builder> accounts)
			throws BlockException {
		TransactionInput oInput = tx.getBody().getInput();
		ByteString address = oInput.getAddress();
		//不在同一个分片上面, 不需要执行
		/*if(oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(oInput.getSliceId())){
			continue;
		}*/
		Account.Builder sender = accounts.get(address);
		AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();
		boolean isExistToken = false;
		for (int i = 0; i < senderAccountValue.getTokensCount(); i++) {
			if (senderAccountValue.getTokens(i).getToken().equals(oInput.getToken())) {
				AccountTokenValue.Builder oAccountTokenValue = senderAccountValue.getTokens(i).toBuilder();
				oAccountTokenValue.setBalance(ByteString.copyFrom(
						ByteUtil.bytesSubToBytes(senderAccountValue.getTokens(i).getBalance().toByteArray(),
								oInput.getAmount().toByteArray())));
				oAccountTokenValue.setLocked(ByteString.copyFrom(
						ByteUtil.bytesAddToBytes(senderAccountValue.getTokens(i).getLocked().toByteArray(),
								oInput.getAmount().toByteArray())));
				senderAccountValue.setTokens(i, oAccountTokenValue);

				isExistToken = true;
				break;
			}
		}
		if (!isExistToken) {
			throw new TransactionExecuteException(
					String.format("cannot found token %s in sender account", oInput.getToken()));
		}

		senderAccountValue.setBalance(senderAccountValue.getBalance());
		senderAccountValue.setNonce(oInput.getNonce() + 1);

		sender.setValue(senderAccountValue);
		accounts.put(sender.getAddress(), sender);

		return ByteString.EMPTY;
	}

	public ActuatorLockTokenTransaction(IAccountHelper oAccountHelper, ITransactionHelper oTransactionHelper,
			BlockEntity oBlock, EncAPI encApi, IStateTrie oStateTrie) {
		super(oAccountHelper, oTransactionHelper, oBlock, encApi,  oStateTrie);
	}

}
