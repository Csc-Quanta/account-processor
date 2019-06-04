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
import org.csc.account.processor.BlockChainConfig;
import org.csc.account.processor.evmapi.EvmApiImp;
import org.csc.account.util.ByteUtil;
import org.csc.bcapi.EncAPI;
import org.csc.bcapi.UnitUtil;
import org.csc.evmapi.gens.Act.Account;
import org.csc.evmapi.gens.Act.AccountValue;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx.Transaction;
import org.csc.evmapi.gens.Tx.TransactionInput;
import org.csc.rcvm.exec.VM;
import org.csc.rcvm.exec.invoke.ProgramInvokeImpl;
import org.csc.rcvm.program.Program;
import org.csc.rcvm.program.ProgramResult;

import java.math.BigInteger;
import java.util.Iterator;
import java.util.Map;

/**
 * 创建合约
 *
 * @author lance
 * @since 2019.1.9 21:27
 */
@Slf4j
public class ActuatorCreateContract extends AbstractTransactionActuator {
    public ActuatorCreateContract(IAccountHelper oAccountHelper, ITransactionHelper oTransactionHelper,
                                  BlockEntity oBlock, EncAPI encApi, IStateTrie oStateTrie) {
        super(oAccountHelper, oTransactionHelper, oBlock, encApi, oStateTrie);
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
        //不在同一个分片上面, 不需要执行
        if (oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(input.getSliceId())) {
            return;
        }

        if (!input.getToken().isEmpty()) {
            throw new TransactionParameterInvalidException("parameter invalid, token must be null");
        }

        //ECR721
        if (!input.getSymbol().isEmpty()|| input.getCryptoTokenCount() != 0) {
            throw new TransactionParameterInvalidException("parameter invalid, crypto token must be null");
        }

        Account.Builder sender = accounts.get(tx.getBody().getInput().getAddress());
        AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();

        BigInteger inputsTotal = BigInteger.ZERO;
        inputsTotal = inputsTotal.add(BlockChainConfig.contract_lock_balance);
        inputsTotal = inputsTotal.add(ByteUtil.bytesToBigInteger(input.getAmount().toByteArray()));

        if (ByteUtil.bytesToBigInteger(senderAccountValue.getBalance().toByteArray())
                .compareTo(inputsTotal) == -1) {
            throw new TransactionParameterInvalidException(String.format("not enough balance %s to create contract",
                    UnitUtil.fromWei(inputsTotal)));
        }

        BigInteger bi = ByteUtil.bytesToBigInteger(input.getAmount().toByteArray());
        if (bi.compareTo(BigInteger.ZERO) < 0) {
            throw new TransactionParameterInvalidException("parameter invalid, amount must large than 0");
        }

        if (senderAccountValue.getSubAddressCount() > 0) {
            throw new TransactionParameterInvalidException(
                    "parameter invalid, union account does not allow to create this transaction");
        }

        int nonce = senderAccountValue.getNonce();
        if (nonce > input.getNonce()) {
            throw new TransactionParameterInvalidException(
                    String.format("parameter invalid, sender nonce %s is not equal with transaction nonce %s", nonce,
                            input.getNonce()));
        }
    }

    @Override
    public ByteString onExecute(Transaction tx, Map<ByteString, Account.Builder> accounts)
            throws BlockException {
        ByteString newContractAddress = oTransactionHelper.getContractAddressByTransaction(tx);
        if (oAccountHelper.isExist(newContractAddress)) {
            throw new TransactionExecuteException("contract address already exists");
        }

        TransactionInput oInput = tx.getBody().getInput();
        //不在同一个分片上面, 不需要执行
        if(oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(oInput.getSliceId())){
            return ByteString.EMPTY;
        }

        Account.Builder contract = oAccountHelper.createAccount(newContractAddress);
        accounts.put(newContractAddress, contract);

        Account.Builder sender = accounts.get(oInput.getAddress());
        AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();

        senderAccountValue.setBalance(ByteString.copyFrom(
                ByteUtil.bigIntegerToBytes(ByteUtil.bytesToBigInteger(senderAccountValue.getBalance().toByteArray())
                        .subtract(BlockChainConfig.contract_lock_balance))));

        senderAccountValue.setBalance(ByteString.copyFrom(ByteUtil
                .bytesSubToBytes(senderAccountValue.getBalance().toByteArray(), oInput.getAmount().toByteArray())));

        senderAccountValue.setNonce(oInput.getNonce() + 1);
        sender.setValue(senderAccountValue);

        accounts.put(sender.getAddress(), sender);

        EvmApiImp evmApiImp = new EvmApiImp();
        evmApiImp.setAccountHelper(oAccountHelper);
        evmApiImp.setTransactionHelper(oTransactionHelper);
        evmApiImp.setEncApi(this.encApi);
		evmApiImp.setMemoryAccount(accounts);
        evmApiImp.getTouchAccount().put(encApi.hexEnc(contract.getAddress().toByteArray()), contract.build());

        ProgramInvokeImpl createProgramInvoke = new ProgramInvokeImpl(newContractAddress.toByteArray(),
                oInput.getAddress().toByteArray(), oInput.getAddress().toByteArray(),
                oInput.getAmount().toByteArray(), ByteUtil.bigIntegerToBytes(BigInteger.ZERO),
                tx.getBody().getData().toByteArray(),
                oBlock.getHeader().getPreHash().toByteArray(),
                oBlock.getMiner().getAddress().toByteArray(),
                oBlock.getHeader().getTimestamp(),
                oBlock.getHeader().getNumber(),
                ByteString.EMPTY.toByteArray(),
                evmApiImp);

        Program createProgram = new Program(tx.getBody().getData().toByteArray(),
                createProgramInvoke, tx);
        VM createVM = new VM();
        createVM.play(createProgram);
        ProgramResult createResult = createProgram.getResult();
        if (createResult.getException() != null) {
            log.error("error on craete contact::" + createResult.getException());
            throw new TransactionExecuteException("error on execute contact");
        } else {
            createResult = createProgram.getResult();

            Account.Builder oCreateAccount = accounts.get(oInput.getAddress());
            AccountValue.Builder oValue = oCreateAccount.getValueBuilder();
            oCreateAccount.setValue(oValue.build());
            accounts.put(oCreateAccount.getAddress(), oCreateAccount);

            Account.Builder locker = accounts.get(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.lock_account_address)));
            AccountValue.Builder lockerAccountValue = locker.getValue().toBuilder();
            lockerAccountValue.setBalance(ByteString.copyFrom(ByteUtil
                    .bigIntegerToBytes(ByteUtil.bytesToBigInteger(lockerAccountValue.getBalance().toByteArray())
                            .add(BlockChainConfig.contract_lock_balance))));

            locker.setValue(lockerAccountValue);
            accounts.put(locker.getAddress(), locker);

            Iterator iter = evmApiImp.getTouchAccount().entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<String, Account> entry = (Map.Entry<String, Account>) iter.next();
                if (entry.getKey().equals(encApi.hexEnc(newContractAddress.toByteArray()))) {
                    Account.Builder touchAccount = ((Account) entry.getValue()).toBuilder();
                    AccountValue.Builder oContractValue = touchAccount.getValueBuilder();
                    oContractValue.setCode(ByteString.copyFrom(createResult.getHReturn()));
                    oContractValue.setCodeHash(
                            ByteString.copyFrom(encApi.sha256Encode(oContractValue.getCode().toByteArray())));
                    oContractValue.setData(tx.getBody().getExtData());
                    oContractValue.addSubAddress(oCreateAccount.getAddress());

                    oContractValue.setBalance(ByteString.copyFrom(ByteUtil.bytesAddToBytes(
                            oContractValue.getBalance().toByteArray(), oInput.getAmount().toByteArray())));

                    contract.setValue(oContractValue);
                    accounts.put(contract.getAddress(), contract);
                } else {
                    accounts.put(ByteString.copyFrom(encApi.hexDec(entry.getKey())), entry.getValue().toBuilder());
                }
            }

            oAccountHelper.createContract(oCreateAccount.getAddress(), contract.getAddress());
        }

        return ByteString.EMPTY;
    }
}

