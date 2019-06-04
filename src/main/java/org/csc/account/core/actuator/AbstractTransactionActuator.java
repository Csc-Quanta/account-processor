package org.csc.account.core.actuator;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.csc.account.api.IAccountHelper;
import org.csc.account.api.IStateTrie;
import org.csc.account.api.ITransactionActuator;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.TransactionParameterInvalidException;
import org.csc.account.exception.TransactionVerifyException;
import org.csc.account.processor.BlockChainConfig;
import org.csc.account.util.ByteUtil;
import org.csc.bcapi.EncAPI;
import org.csc.evmapi.gens.Act.Account;
import org.csc.evmapi.gens.Act.AccountValue;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx.Transaction;
import org.csc.evmapi.gens.Tx.TransactionBody;
import org.csc.evmapi.gens.Tx.TransactionInput;
import org.csc.evmapi.gens.Tx.TransactionOutput;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.csc.account.processor.BlockChainConfig.isDisableEC;

/**
 * 交易执行抽象类
 * @author lance
 * @since 2019.1.8 23:38
 */
@Slf4j
public abstract class AbstractTransactionActuator implements ITransactionActuator {
	protected Map<ByteString, Transaction> txValues = new HashMap<>();

	@Override
	public Map<ByteString, Transaction> getTxValues() {
		return txValues;
	}

	@Override
	public void onVerifySignature(Transaction tx, Map<ByteString, Account.Builder> accounts)
			throws BlockException {
		List<String> inputAddresses = new ArrayList<>();
		if(isDisableEC){
			log.debug("ec check disabled.");
			return;
		}

		//发起者
		String address = encApi.hexEnc(tx.getBody().getInput().getAddress().toByteArray());
		inputAddresses.add(address);

		// 获取交易原始encode
		Transaction.Builder txBuilder = tx.toBuilder();
		TransactionBody.Builder txBody = txBuilder.getBodyBuilder();
		txBuilder.clearHash();
		txBody = txBody.clearSignatures();
		byte[]bytes = txBody.build().toByteArray();

		// 校验交易签名
		byte[] pubKey = encApi.ecToKeyBytes(bytes, encApi.hexEnc(tx.getBody().getSignatures().toByteArray()));
		String hexAddress = encApi.ecToAddressHex(bytes, encApi.hexEnc(tx.getBody().getSignatures().toByteArray()));
		if(log.isDebugEnabled()){
			log.debug("===>InputAddress: {}, hex: {}", inputAddresses, hexAddress);
		}

		if (inputAddresses.remove(hexAddress)) {
			if (!encApi.ecVerify(pubKey, bytes, tx.getBody().getSignatures().toByteArray())) {
				throw new TransactionVerifyException(String.format("signature %s verify fail with pubkey %s",
						encApi.hexEnc(tx.getBody().getSignatures().toByteArray()),
						encApi.hexEnc(pubKey)));
			}
		} else {
			throw new TransactionVerifyException(
					String.format("signature cannot find sign address %s", hexAddress));
		}

		if (!inputAddresses.isEmpty()) {
			throw new TransactionVerifyException(
					String.format("signature cannot match address %s", inputAddresses.get(0)));
		}
	}

	protected IAccountHelper oAccountHelper;
	protected ITransactionHelper oTransactionHelper;
	protected BlockEntity oBlock;
	protected EncAPI encApi;
	protected IStateTrie oIStateTrie;

	public AbstractTransactionActuator(IAccountHelper oIAccountHelper, ITransactionHelper oTransactionHelper,
			BlockEntity currentBlock, EncAPI encApi, IStateTrie oIStateTrie) {
		this.oAccountHelper = oIAccountHelper;
		this.oTransactionHelper = oTransactionHelper;
		this.oBlock = currentBlock;
		this.encApi = encApi;

		this.oIStateTrie = oIStateTrie;
	}

	public void reset(IAccountHelper oIAccountHelper, ITransactionHelper oTransactionHelper, BlockEntity currentBlock,
			EncAPI encApi, IStateTrie oIStateTrie) {
		this.oAccountHelper = oIAccountHelper;
		this.oTransactionHelper = oTransactionHelper;
		this.oBlock = currentBlock;
		this.encApi = encApi;
		this.oIStateTrie = oIStateTrie;
		this.txValues.clear();
	}

	@Override
	public boolean needSignature() {
		return true;
	}

	/**
	 * 字符串格式
	 * @param expression	格式表达式
	 * @param params		参数
	 * @return				格式后的字符串
	 */
	public String format(String expression, Object...params){
		return String.format(expression, params);
	}

	@Override
	public void onPrepareExecute(Transaction tx, Map<ByteString, Account.Builder> accounts)
			throws BlockException {
		//BigInteger inputsTotal = BigInteger.ZERO;
		BigInteger outputsTotal = BigInteger.ZERO;
		//发起者

		TransactionInput input = tx.getBody().getInput();
		BigInteger inputsTotal = ByteUtil.bytesToBigInteger(input.getAmount().toByteArray());
		if (inputsTotal.compareTo(BigInteger.ZERO) < 0) {
			throw new TransactionParameterInvalidException("parameter invalid, amount must large than 0");
		}

		//不在同一个分片上面, 不需要执行
		if(oAccountHelper.canExecute(input.getSliceId())){
			Account.Builder sender = accounts.get(input.getAddress());
			AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();

			if (senderAccountValue.getSubAddressCount() > 0) {
				throw new TransactionParameterInvalidException(
						"parameter invalid, union account does not allow to create this transaction");
			}

			BigInteger balance = ByteUtil.bytesToBigInteger(senderAccountValue.getBalance().toByteArray());

			if (balance.compareTo(BigInteger.ZERO) == -1) {
				throw new TransactionParameterInvalidException(
						String.format("parameter invalid, sender %s balance %s less than 0",
								encApi.hexEnc(sender.getAddress().toByteArray()), balance));
			}
			if (ByteUtil.bytesToBigInteger(input.getAmount().toByteArray()).compareTo(BigInteger.ZERO) == -1) {
				throw new TransactionParameterInvalidException(
						String.format("parameter invalid, transaction value %s less than 0",
								ByteUtil.bytesToBigInteger(input.getAmount().toByteArray())));
			}

			if (ByteUtil.bytesToBigInteger(balance.toByteArray()).compareTo(inputsTotal) == -1) {
				throw new TransactionParameterInvalidException(
						String.format("parameter invalid, sender balance %s less than %s", balance,
								ByteUtil.bytesToBigInteger(input.getAmount().toByteArray())));
			}

			/*int nonce = senderAccountValue.getNonce();
			if (nonce > input.getNonce() ) {
				throw new TransactionParameterInvalidException(
						String.format("parameter invalid, sender nonce %s is not equal with transaction nonce %s",
								nonce, input.getNonce()));
			}*/
		}

		for (TransactionOutput output: tx.getBody().getOutputsList()) {
			if (!oAccountHelper.canExecute(output.getSliceId())) {
				continue;
			}
			BigInteger bi = ByteUtil.bytesToBigInteger(output.getAmount().toByteArray());
			if (bi.compareTo(BigInteger.ZERO) < 0) {
				throw new TransactionParameterInvalidException("parameter invalid, amount must large than 0");
			}
			outputsTotal = outputsTotal.add(bi);

			BigInteger balance = ByteUtil.bytesToBigInteger(output.getAmount().toByteArray());
			if (balance.compareTo(BigInteger.ZERO) == -1) {
				throw new TransactionParameterInvalidException(
						String.format("parameter invalid, receive balance %s less than 0", balance));
			}
		}

		if (inputsTotal.compareTo(outputsTotal) != 0) {
			throw new TransactionParameterInvalidException(String
					.format("parameter invalid, transaction value %s not equal with %s", inputsTotal, outputsTotal));
		}
	}

	@Override
	public ByteString onExecute(Transaction tx, Map<ByteString, Account.Builder> accounts)
			throws BlockException {

		//不在同一个分片上面, 不需要执行
		TransactionInput input = tx.getBody().getInput();
		
		
		if(oAccountHelper.canExecute(input.getSliceId())){
			Account.Builder sender = accounts.get(input.getAddress());
			AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();
			
			senderAccountValue.setBalance(ByteString.copyFrom(ByteUtil
					.bytesSubToBytes(senderAccountValue.getBalance().toByteArray(), input.getAmount().toByteArray())));

			senderAccountValue.setNonce(input.getNonce() + 1);

			sender.setValue(senderAccountValue);
			accounts.put(sender.getAddress(), sender);
		}

		for (TransactionOutput output : tx.getBody().getOutputsList()) {
			///不在同一个分片上面, 不需要执行
            if(!oAccountHelper.canExecute(output.getSliceId())){
                continue;
            }
			Account.Builder receiver = accounts.get(output.getAddress());
			if (receiver == null) {
				receiver = oAccountHelper.createAccount(output.getAddress());
			}
			AccountValue.Builder receiverAccountValue = receiver.getValue().toBuilder();
			receiverAccountValue.setBalance(ByteString.copyFrom(ByteUtil.bytesAddToBytes(
					receiverAccountValue.getBalance().toByteArray(), output.getAmount().toByteArray())));

			receiver.setValue(receiverAccountValue);
			accounts.put(receiver.getAddress(), receiver);
		}

		return ByteString.EMPTY;
	}

	@Override
	public void onExecuteDone(Transaction oMultiTransaction, BlockEntity be, ByteString result)
			throws BlockException {
		oTransactionHelper.setTransactionDone(oMultiTransaction, be, result);
	}

	@Override
	public void onExecuteError(Transaction oMultiTransaction, BlockEntity be, ByteString result)
			throws BlockException {
		oTransactionHelper.setTransactionError(oMultiTransaction, be, result);
	}
}
