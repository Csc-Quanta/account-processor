package org.csc.account.core.actuator.token;

import com.google.protobuf.ByteString;
import org.csc.account.api.IAccountHelper;
import org.csc.account.api.IStateTrie;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.core.actuator.AbstractTransactionActuator;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.TransactionParameterInvalidException;
import org.csc.account.processor.BlockChainConfig;
import org.csc.account.util.ByteUtil;
import org.csc.bcapi.EncAPI;
import org.csc.bcapi.UnitUtil;
import org.csc.evmapi.gens.Act.*;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx.Transaction;
import org.csc.evmapi.gens.Tx.TransactionInput;

import java.util.Map;

/**
 * 发起Token.ERC20交易, 在分片上处理 发布token交易。 发送方token=发布总量
 * 
 * @author lance
 * @since 2019.1.8 23:46
 */
public class ActuatorCreateToken extends AbstractTransactionActuator {

	public ActuatorCreateToken(IAccountHelper oAccountHelper, ITransactionHelper oTransactionHelper, BlockEntity oBlock,
			EncAPI encApi, IStateTrie oStateTrie) {
		super(oAccountHelper, oTransactionHelper, oBlock, encApi, oStateTrie);
	}

	@Override
	public ByteString onExecute(Transaction tx, Map<ByteString, Account.Builder> accounts)
			throws BlockException {
		TransactionInput input = tx.getBody().getInput();
		ByteString address = input.getAddress();

		//不在同一个分片上面, 不需要执行
		if(oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(input.getSliceId())){
			return ByteString.EMPTY;
		}

		Account.Builder sender = accounts.get(address);
		AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();

		AccountTokenValue.Builder oAccountTokenValue = AccountTokenValue.newBuilder();
		oAccountTokenValue.setBalance(input.getAmount()).setToken(input.getToken());

		senderAccountValue.addTokens(oAccountTokenValue)
				.setBalance(ByteString.copyFrom(ByteUtil
						.bigIntegerToBytes(ByteUtil.bytesToBigInteger(senderAccountValue.getBalance().toByteArray())
								.subtract(BlockChainConfig.token_lock_balance))));
		senderAccountValue.setNonce(input.getNonce() + 1);

		ERC20TokenValue.Builder oICOValue = ERC20TokenValue.newBuilder();
		oICOValue.setAddress(encApi.hexEnc(sender.getAddress().toByteArray()));
		oICOValue.setTimestamp(tx.getBody().getTimestamp());
		oICOValue.setToken(input.getToken().toStringUtf8());
		oICOValue.setTotalSupply(ByteString.copyFrom(ByteUtil
				.bigIntegerToBytes(UnitUtil.fromWei(ByteUtil.bytesToBigInteger(input.getAmount().toByteArray())))));

		ERC20TokenValueHistory.Builder oERC20TokenValueHistory = ERC20TokenValueHistory.newBuilder();
		oERC20TokenValueHistory.setContent("C");
		oERC20TokenValueHistory.setTotalSupply(oICOValue.getTotalSupply());
		oERC20TokenValueHistory.setTimestamp(tx.getBody().getTimestamp());
		oICOValue.addHistory(oERC20TokenValueHistory);

		Account.Builder oTokenRecordAccount = accounts.get(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.token_record_account_address)));
		oAccountHelper.putStorage(oTokenRecordAccount, input.getToken().toByteArray(), oICOValue.build().toByteArray());

		sender.setValue(senderAccountValue);

		Account.Builder locker = accounts.get(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.lock_account_address)));
		AccountValue.Builder lockerAccountValue = locker.getValue().toBuilder();
		lockerAccountValue.setBalance(ByteString.copyFrom(
				ByteUtil.bigIntegerToBytes(ByteUtil.bytesToBigInteger(lockerAccountValue.getBalance().toByteArray())
						.add(BlockChainConfig.token_lock_balance))));

		locker.setValue(lockerAccountValue);

		accounts.put(sender.getAddress(), sender);
		accounts.put(locker.getAddress(), locker);
		accounts.put(oTokenRecordAccount.getAddress(), oTokenRecordAccount);
		return ByteString.EMPTY;
	}

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
		ByteString address = oInput.getAddress();
		//不在同一个分片上面, 不需要执行
		if(oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(oInput.getSliceId())){
			return ;
		}

		String token = oInput.getToken().toStringUtf8();
		if (token == null || token.isEmpty()) {
			throw new TransactionParameterInvalidException("parameter invalid, token name must not be empty");
		}

		String prefix = "CSC";
		if (token.toUpperCase().startsWith(prefix)) {
			throw new TransactionParameterInvalidException("parameter invalid, token name can't start with CSC");
		}

		if (token.length() > 8) {
			throw new TransactionParameterInvalidException("parameter invalid, token name too long");
		}

		if (!token.toUpperCase().equals(token)) {
			throw new TransactionParameterInvalidException("parameter invalid, token name invalid");
		}

		if (ByteUtil.bytesToBigInteger(oInput.getAmount().toByteArray()).compareTo(BlockChainConfig.minerReward) == -1
				|| ByteUtil.bytesToBigInteger(oInput.getAmount().toByteArray())
						.compareTo(BlockChainConfig.maxTokenTotal) == 1) {
			throw new TransactionParameterInvalidException(
					String.format("parameter invalid, token amount must between %s and %s ",
							UnitUtil.fromWei(BlockChainConfig.minTokenTotal),
							UnitUtil.fromWei(BlockChainConfig.maxTokenTotal)));
		}

		Account.Builder sender = accounts.get(address);
		AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();
		if (ByteUtil.bytesToBigInteger(senderAccountValue.getBalance().toByteArray())
				.compareTo(BlockChainConfig.token_lock_balance) == -1) {
			throw new TransactionParameterInvalidException(String.format(
					"parameter invalid, not enough deposit %s to create token", BlockChainConfig.token_lock_balance));
		}

		Account.Builder oTokenRecordAccount = accounts.get(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.token_record_account_address)));
		byte[] tokenRecord = oAccountHelper.getStorage(oTokenRecordAccount, token.getBytes());
		if (tokenRecord != null) {
			throw new TransactionParameterInvalidException(
					String.format("parameter invalid, duplicate token name %s", token));
		}

		int nonce = senderAccountValue.getNonce();
		if (nonce > oInput.getNonce()) {
			throw new TransactionParameterInvalidException(
					String.format("sender nonce %s is not equal with transaction nonce %s", nonce, oInput.getNonce()));
		}
	}
}