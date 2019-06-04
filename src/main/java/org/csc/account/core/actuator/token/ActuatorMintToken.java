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
 * Token增发, 不需要处理, 在V4Process, 向StateTrie增加时, 自动过滤分片账户
 * 
 * @author lance
 * @since 2019.1.9 21:33
 */
public class ActuatorMintToken extends AbstractTransactionActuator {

	public ActuatorMintToken(IAccountHelper oAccountHelper, ITransactionHelper oTransactionHelper, BlockEntity oBlock,
			EncAPI encApi, IStateTrie oStateTrie) {
		super(oAccountHelper, oTransactionHelper, oBlock, encApi, oStateTrie);
	}

	@Override
	public ByteString onExecute(Transaction tx, Map<ByteString, Account.Builder> accounts)
			throws BlockException {
		TransactionInput input = tx.getBody().getInput();
		//不在同一个分片上面, 不需要执行
		if(oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(input.getSliceId())){
			return ByteString.EMPTY;
		}

		Account.Builder sender = accounts.get(input.getAddress());
		AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();

		// set account token balance
		for (AccountTokenValue.Builder oAccountTokenValue : senderAccountValue.getTokensBuilderList()) {
			if (oAccountTokenValue.getToken().equals(input.getToken())) {
				oAccountTokenValue.setBalance(ByteString.copyFrom(ByteUtil
						.bigIntegerToBytes(ByteUtil.bytesToBigInteger(oAccountTokenValue.getBalance().toByteArray())
								.add(ByteUtil.bytesToBigInteger(input.getAmount().toByteArray())))));
			}
		}

		senderAccountValue.setBalance(ByteString.copyFrom(
				ByteUtil.bigIntegerToBytes(ByteUtil.bytesToBigInteger(senderAccountValue.getBalance().toByteArray())
						.subtract(BlockChainConfig.token_mint_balance))));
		senderAccountValue.setNonce(input.getNonce() + 1);

		// set token owner
		Account.Builder oTokenRecordAccount = accounts.get(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.token_record_account_address)));
		ERC20TokenValue.Builder oERC20TokenValue = null;
		byte[] tokenRecord = oAccountHelper.getStorage(oTokenRecordAccount, input.getToken().toByteArray());
		try {
			oERC20TokenValue = ERC20TokenValue.parseFrom(tokenRecord).toBuilder();
		} catch (Exception e) {

		}

		oERC20TokenValue.setTotalSupply(ByteString.copyFrom(
				ByteUtil.bigIntegerToBytes(ByteUtil.bytesToBigInteger(oERC20TokenValue.getTotalSupply().toByteArray())
						.add(UnitUtil.fromWei(ByteUtil.bytesToBigInteger(input.getAmount().toByteArray()))))));

		ERC20TokenValueHistory.Builder oERC20TokenValueHistory = ERC20TokenValueHistory.newBuilder();
		oERC20TokenValueHistory.setContent("M");
		oERC20TokenValueHistory.setTotalSupply(oERC20TokenValue.getTotalSupply());
		oERC20TokenValueHistory.setTimestamp(tx.getBody().getTimestamp());
		oERC20TokenValue.addHistory(oERC20TokenValueHistory);
		oAccountHelper.putStorage(oTokenRecordAccount, input.getToken().toByteArray(),
				oERC20TokenValue.build().toByteArray());

		sender.setValue(senderAccountValue);

		Account.Builder locker = accounts.get(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.lock_account_address)));
		AccountValue.Builder lockerAccountValue = locker.getValue().toBuilder();
		lockerAccountValue.setBalance(ByteString.copyFrom(
				ByteUtil.bigIntegerToBytes(ByteUtil.bytesToBigInteger(lockerAccountValue.getBalance().toByteArray())
						.add(BlockChainConfig.token_mint_balance))));

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
        //不在同一个分片上面, 不需要执行
        if(oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(oInput.getSliceId())){
            return;
        }

		String token = oInput.getToken().toStringUtf8();
		if (token == null || token.isEmpty()) {
			throw new TransactionParameterInvalidException("parameter invalid, token name must not be empty");
		}

		if (!token.toUpperCase().equals(token)) {
			throw new TransactionParameterInvalidException("parameter invalid, token name invalid");
		}

		Account.Builder sender = accounts.get(oInput.getAddress());
		AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();
		if (ByteUtil.bytesToBigInteger(senderAccountValue.getBalance().toByteArray())
				.compareTo(BlockChainConfig.token_mint_balance) == -1) {
			throw new TransactionParameterInvalidException(String.format(
					"parameter invalid, not enough deposit %s to create token", BlockChainConfig.token_mint_balance));
		}

		Account.Builder oTokenRecordAccount = accounts.get(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.token_record_account_address)));
		ERC20TokenValue.Builder oERC20TokenValue = null;
		byte[] tokenRecord = oAccountHelper.getStorage(oTokenRecordAccount, token.getBytes());
		if (tokenRecord != null) {
			try {
				oERC20TokenValue = ERC20TokenValue.parseFrom(tokenRecord).toBuilder();
			} catch (Exception e) {

			}
			if (oERC20TokenValue == null
					|| !oERC20TokenValue.getAddress().equals(encApi.hexEnc(oInput.getAddress().toByteArray()))) {
				throw new TransactionParameterInvalidException(
						String.format("parameter invalid, token %s not exists", token));
			}
		} else {
			throw new TransactionParameterInvalidException(
					String.format("parameter invalid, token %s not exists", token));
		}

		int nonce = senderAccountValue.getNonce();
		if (nonce > oInput.getNonce()) {
			throw new TransactionParameterInvalidException(
					String.format("sender nonce %s is not equal with transaction nonce %s", nonce, oInput.getNonce()));
		}
	}
}