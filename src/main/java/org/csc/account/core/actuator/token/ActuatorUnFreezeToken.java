package org.csc.account.core.actuator.token;

import com.google.common.collect.Lists;
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
import org.csc.evmapi.gens.Act.Account;
import org.csc.evmapi.gens.Act.AccountTokenValue;
import org.csc.evmapi.gens.Act.AccountValue;
import org.csc.evmapi.gens.Act.ERC20TokenValue;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx.Transaction;
import org.csc.evmapi.gens.Tx.TransactionInput;
import org.csc.evmapi.gens.Tx.TransactionOutput;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * token持有者对发出去的token进行解冻操作
 * 
 * @author lance
 * @since 2019.1.9 20:49
 */
public class ActuatorUnFreezeToken extends AbstractTransactionActuator {

	public ActuatorUnFreezeToken(IAccountHelper oAccountHelper, ITransactionHelper oTransactionHelper,
			BlockEntity oBlock, EncAPI encApi, IStateTrie oStateTrie) {
		super(oAccountHelper, oTransactionHelper, oBlock, encApi, oStateTrie);
	}

	@Override
	public ByteString onExecute(Transaction tx, Map<ByteString, Account.Builder> accounts)
			throws BlockException {
		ByteString token = tx.getBody().getInput().getToken();
		for (TransactionOutput oOutput : tx.getBody().getOutputsList()) {
			// 不在同一个分片上面, 不需要执行
			if (oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(oOutput.getSliceId())) {
				continue;
			}

			Account.Builder receiver = accounts.get(oOutput.getAddress());
			AccountValue.Builder receiverAccountValue = receiver.getValue().toBuilder();

			for (AccountTokenValue.Builder oAccountTokenValue : receiverAccountValue.getTokensBuilderList()) {
				if (Objects.equals(oAccountTokenValue.getToken(), token)) {
					oAccountTokenValue.setFreeze(ByteString.copyFrom(ByteUtil.bytesSubToBytes(
							oAccountTokenValue.getBalance().toByteArray(), oOutput.getAmount().toByteArray())));
					oAccountTokenValue.setBalance(ByteString.copyFrom(ByteUtil.bytesAddToBytes(
							oAccountTokenValue.getLocked().toByteArray(), oOutput.getAmount().toByteArray())));
				}
			}

			receiver.setValue(receiverAccountValue);
			accounts.put(receiver.getAddress(), receiver);
		}

		// 不在同一个分片上面, 不需要执行
		TransactionInput input = tx.getBody().getInput();
		if (oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(input.getSliceId())) {
			return ByteString.EMPTY;
		}

		Account.Builder sender = accounts.get(input.getAddress());
		AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();

		senderAccountValue.setNonce(input.getNonce() + 1);
		sender.setValue(senderAccountValue);
		accounts.put(sender.getAddress(), sender);
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

		TransactionInput input = tx.getBody().getInput();
		BigInteger bi = ByteUtil.bytesToBigInteger(input.getAmount().toByteArray());
		if (oAccountHelper.canExecute(input.getSliceId())) {
			// token的发行方可以冻结
			ByteString tokenName = input.getToken();
			Account.Builder oTokenRecordAccount = accounts.get(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.token_record_account_address)));
			ERC20TokenValue.Builder oERC20TokenValue = null;
			byte[] tokenRecord = oAccountHelper.getStorage(oTokenRecordAccount, tokenName.toByteArray());
			if (tokenRecord != null) {
				try {
					oERC20TokenValue = ERC20TokenValue.parseFrom(tokenRecord).toBuilder();
				} catch (Exception e) {

				}
				if (oERC20TokenValue == null
						|| !oERC20TokenValue.getAddress().equals(encApi.hexEnc(input.getAddress().toByteArray()))) {
					throw new TransactionParameterInvalidException(
							String.format("parameter invalid, token %s not exists", tokenName));
				}
			} else {
				throw new TransactionParameterInvalidException(
						String.format("parameter invalid, token %s not exists", tokenName));
			}
		}

		Account.Builder sender = accounts.get(input.getAddress());
		AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();

		// 判断是否有足够的token可以被冻结
		// 判断冻结总额和input是否一致
		// output里的地址不允许重复
		BigInteger tokenBalance = BigInteger.ZERO;
		List<String> outAddress = Lists.newArrayList();
		for (TransactionOutput output: tx.getBody().getOutputsList()) {
			tokenBalance = tokenBalance.add(ByteUtil.bytesToBigInteger(output.getAmount().toByteArray()));

			// 不在同一个分片上面, 不需要执行
			if (oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(output.getSliceId())) {
				continue;
			}

			String address = encApi.hexEnc(output.getAddress().toByteArray());
			Account.Builder receiver = accounts.get(output.getAddress());
			AccountValue.Builder receiverAccountValue = receiver.getValue().toBuilder();

			if (outAddress.contains(address)) {
				throw new TransactionParameterInvalidException("parameter invalid, duplicate output address");
			}
			outAddress.add(address);
			BigInteger freezeAmount = ByteUtil.bytesToBigInteger(output.getAmount().toByteArray());
			for (int i = 0; i < receiverAccountValue.getTokensCount(); i++) {
				if (receiverAccountValue.getTokens(i).getToken().equals(input.getToken())) {
					if (ByteUtil.bytesToBigInteger(receiverAccountValue.getTokens(i).getBalance().toByteArray())
							.compareTo(freezeAmount) < 0) {
						throw new TransactionParameterInvalidException("parameter invalid, no enouth token to freeze");
					} else {
						tokenBalance = tokenBalance.add(freezeAmount);
					}
				}
			}
		}
		if (tokenBalance.compareTo(bi) != 0) {
			throw new TransactionParameterInvalidException("parameter invalid, transaction value not equal ");
		}

		int nonce = senderAccountValue.getNonce();
		if (nonce > input.getNonce()) {
			throw new TransactionParameterInvalidException(
					String.format("parameter invalid, sender nonce %s is not equal with transaction nonce %s", nonce,
							input.getNonce()));
		}
	}
}