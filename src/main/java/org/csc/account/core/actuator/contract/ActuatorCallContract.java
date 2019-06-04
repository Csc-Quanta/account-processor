package org.csc.account.core.actuator.contract;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.csc.account.api.IAccountHelper;
import org.csc.account.api.IStateTrie;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.core.actuator.AbstractTransactionActuator;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.TransactionExecuteException;
import org.csc.account.exception.TransactionParameterInvalidException;
import org.csc.account.processor.evmapi.EvmApiImp;
import org.csc.account.util.ByteUtil;
import org.csc.bcapi.EncAPI;
import org.csc.evmapi.gens.Act.Account;
import org.csc.evmapi.gens.Act.Account.Builder;
import org.csc.evmapi.gens.Act.AccountValue;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx.Transaction;
import org.csc.evmapi.gens.Tx.TransactionInput;
import org.csc.evmapi.gens.Tx.TransactionOutput;
import org.csc.rcvm.exec.VM;
import org.csc.rcvm.exec.invoke.ProgramInvokeImpl;
import org.csc.rcvm.program.Program;
import org.csc.rcvm.program.ProgramResult;

import java.math.BigInteger;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

/**
 * 调用合约, 需要特殊处理
 * @author lance
 * @since 2019.1.9 21:08
 */
@Slf4j
public class ActuatorCallContract extends AbstractTransactionActuator {

	public ActuatorCallContract(IAccountHelper oAccountHelper, ITransactionHelper oTransactionHelper,
			BlockEntity oBlock, EncAPI encApi, IStateTrie oStateTrie) {
		super(oAccountHelper, oTransactionHelper, oBlock, encApi, oStateTrie);
	}

	@Override
	public void onPrepareExecute(Transaction tx, Map<ByteString, Builder> accounts)throws BlockException {
		if (tx.getBody().getInput() == null || tx.getBody().getOutputsCount() != 1) {
			throw new TransactionParameterInvalidException(
					"parameter invalid, the inputs and outputs must be only one");
		}

		TransactionInput input = tx.getBody().getInput();
		if (!input.getToken().isEmpty()) {
			throw new TransactionParameterInvalidException("parameter invalid, token must be null");
		}

		//ECR721
		if (!input.getSymbol().isEmpty()|| input.getCryptoTokenCount() != 0) {
			throw new TransactionParameterInvalidException("parameter invalid, crypto token must be null");
		} 

		//验证是否在同一个分片上面
		TransactionOutput output = tx.getBody().getOutputs(0);
		ByteString address = output.getAddress();
		if (oAccountHelper.canExecute(output.getSliceId()) && !oAccountHelper.isContract(address)) {
			throw new TransactionParameterInvalidException("parameter invalid, address "
					+ encApi.hexEnc(output.getAddress().toByteArray()) + " is not validate contract.");
		}

		super.onPrepareExecute(tx, accounts);
	}

	@Override
	public ByteString onExecute(Transaction tx, Map<ByteString, Builder> accounts)throws BlockException {
		VM vm = new VM();
		Account.Builder existsContract = accounts.get(tx.getBody().getOutputs(0).getAddress());
		Account.Builder callAccount = accounts.get(tx.getBody().getInput().getAddress());

		AccountValue.Builder senderAccountValue = callAccount.getValue().toBuilder();
		senderAccountValue.setNonce(tx.getBody().getInput().getNonce()+1);
		callAccount.setValue(senderAccountValue);
		accounts.put(callAccount.getAddress(), callAccount);

		EvmApiImp evmApiImp = new EvmApiImp();
		evmApiImp.setAccountHelper(oAccountHelper);
		evmApiImp.setTransactionHelper(oTransactionHelper);
		evmApiImp.setEncApi(this.encApi);
		evmApiImp.setMemoryAccount(accounts);

		TransactionInput input = tx.getBody().getInput();
		ProgramInvokeImpl programInvoke = new ProgramInvokeImpl(existsContract.getAddress().toByteArray(),
				callAccount.getAddress().toByteArray(), callAccount.getAddress().toByteArray(),
				input.getAmount().toByteArray(), ByteUtil.bigIntegerToBytes(BigInteger.ZERO),
				tx.getBody().getData().toByteArray(),
				oBlock.getHeader().getPreHash().toByteArray(),
				null,
				oBlock.getHeader().getTimestamp(),
				oBlock.getHeader().getNumber(),
				ByteString.EMPTY.toByteArray(),
				evmApiImp);

		Program program = new Program(existsContract.getValue().getCodeHash().toByteArray(),
				existsContract.getValue().getCode().toByteArray(), programInvoke, tx);
		vm.play(program);
		ProgramResult result = program.getResult();

		if (result.getException() != null || result.isRevert()) {
			if (result.getException() != null) {
				// throw result.getException();
				log.error("error on execute contact::" + result.getException());
				throw new TransactionExecuteException("error on execute contact");
			} else {
				throw new TransactionExecuteException("REVERT opcode executed");
			}
		} else {
			Iterator iter = evmApiImp.getTouchAccount().entrySet().iterator();
			while (iter.hasNext()) {
				Map.Entry<String, Account> entry = (Entry<String, Account>) iter.next();
				if (entry.getKey().equals(encApi.hexEnc(callAccount.getAddress().toByteArray()))) {
					Account.Builder touchAccount = entry.getValue().toBuilder();
					touchAccount
							.setValue(touchAccount.getValueBuilder().setNonce(touchAccount.getValue().getNonce() + 1));
					accounts.put(callAccount.getAddress(), touchAccount);
				} else {
					accounts.put(ByteString.copyFrom(encApi.hexDec(entry.getKey())), entry.getValue().toBuilder());
				}
			}
			return ByteString.copyFrom(result.getHReturn());
		}
	}
}
