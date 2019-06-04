package org.csc.account.core.actuator.side;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.csc.account.api.IAccountHelper;
import org.csc.account.api.IStateTrie;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.core.actuator.AbstractTransactionActuator;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.TransactionException;
import org.csc.account.exception.TransactionExecuteException;
import org.csc.account.exception.TransactionParameterInvalidException;
import org.csc.account.processor.BlockChainConfig;
import org.csc.account.util.ByteUtil;
import org.csc.bcapi.EncAPI;
import org.csc.bcapi.KeyPairs;
import org.csc.evmapi.gens.Act.Account;
import org.csc.evmapi.gens.Act.AccountValue;
import org.csc.evmapi.gens.Act.SideChainValue;
import org.csc.evmapi.gens.Block;
import org.csc.evmapi.gens.Tx;

import java.math.BigInteger;
import java.util.Map;

/**
 * 子链注册
 * @author lance
 * @since 2019.2.25 16:06
 */
@Slf4j
public class CreateSideChainTransaction extends AbstractTransactionActuator {

    public CreateSideChainTransaction(IAccountHelper accountHelper, ITransactionHelper transactionHelper,
                                 Block.BlockEntity block, EncAPI encApi, IStateTrie stateTrie) {
        super(accountHelper, transactionHelper, block, encApi, stateTrie);
    }

    /**
     * 交易预处理
     */
    @Override
    public void onPrepareExecute(Tx.Transaction tx, Map<ByteString, Account.Builder> accounts) throws BlockException {
        Tx.TransactionInput input = tx.getBody().getInput();
        //验证分片
        if (oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(input.getSliceId())) {
            return;
        }

        validTx(tx, accounts);
    }

    /**交易执行*/
    @Override
    public ByteString onExecute(Tx.Transaction tx, Map<ByteString, Account.Builder> accounts) throws BlockException {
        Tx.TransactionInput input = tx.getBody().getInput();
        //验证分片
        if (oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(input.getSliceId())) {
            return ByteString.EMPTY;
        }

        //验证交易
        validTx(tx, accounts);
        Account.Builder account = accounts.get(input.getAddress());
        AccountValue.Builder  value = account.getValue().toBuilder();
        //更新发起者账户余额, nonce
        BigInteger fee = BlockChainConfig.SIDE_CREATE_ACCOUNT_FEE;
        if(fee.compareTo(BigInteger.ZERO) > 0){
            BigInteger balance = ByteUtil.bytesToBigInteger(value.getBalance().toByteArray());
            value.setBalance(ByteString.copyFrom(ByteUtil.bigIntegerToBytes(balance.subtract(fee))));
        }
        value.setNonce(input.getNonce() + 1);
        account.setValue(value);
        accounts.put(input.getAddress(), account);

        //默认账户地址
        ByteString sideKey = ByteString.copyFrom(encApi.hexDec(BlockChainConfig.sidechain_record_account_address));
        try {
            //新账户地址
            ByteString address = oTransactionHelper.getContractAddressByTransaction(tx);
            if (oAccountHelper.isExist(address)) {
                throw new TransactionExecuteException("Side address already exists");
            }

            //普通账户信息
            Account.Builder normalAccount = accounts.get(sideKey);
            normalAccount = normalAccount == null? Account.newBuilder().setAddress(sideKey): normalAccount;
            ByteString sideValue = createSideValue(address);
            oAccountHelper.putStorage(normalAccount, address.toByteArray(), sideValue.toByteArray());
            accounts.put(sideKey, normalAccount);

            
            //联合账户信息
            Tx.UnionAccountData request = Tx.UnionAccountData.parseFrom(tx.getBody().getData().toByteArray());
            ByteString zero = ByteString.copyFrom(BigInteger.ZERO.toByteArray());
            Account.Builder defAccount = oAccountHelper.createUnionAccount(address, zero, zero,0,request.getAddressList());
            oAccountHelper.putStorage(defAccount, defAccount.getAddress().toByteArray(),sideValue.toByteArray());
            accounts.put(address, defAccount);
            
            return address;
        } catch (InvalidProtocolBufferException e) {
            log.error("===>SideChain Reg fail.{}",e.getMessage());
            throw new TransactionException(e);
        }
    }

    /**
     * 创建默认SideChainValue对象
     * @return        ByteString
     */
    private ByteString createSideValue(ByteString address){
        SideChainValue.Builder value = SideChainValue.newBuilder();
        value.setSideChainAddress(address);
        return value.build().toByteString();
    }

    /**
     * 验证交易是否正确
     * @param tx        输入信息
     * @param accounts  参与交易的用户账户集合
     */
    private void validTx(Tx.Transaction tx, Map<ByteString, Account.Builder> accounts){
        Tx.TransactionInput input = tx.getBody().getInput();
        if (input == null) {
            throw new TransactionParameterInvalidException("parameter invalid, inputs must be only one");
        }
        //验证创建者账户余额是否大于规定手续费
        BigInteger fee = BlockChainConfig.SIDE_CREATE_ACCOUNT_FEE;
        Account.Builder account = accounts.get(input.getAddress());
        AccountValue.Builder value = account.getValue().toBuilder();
        BigInteger amount = ByteUtil.bytesToBigInteger(value.getBalance().toByteArray());

        if (amount.compareTo(fee) < 0) {
            throw new TransactionParameterInvalidException("parameter invalid, amount must be zero");
        }
        //output必须为null
        if (tx.getBody().getOutputsCount() != 0) {
            throw new TransactionParameterInvalidException("parameter invalid, outputs must be null");
        }
        //交易必须签名
        if (tx.getBody().getSignatures().isEmpty()) {
            throw new TransactionParameterInvalidException(
                    "parameter invalid, inputs count must equal with signatures count");
        }

        if (value.getNonce() > input.getNonce()) {
            throw new TransactionParameterInvalidException(
                    String.format("parameter invalid, sender nonce %s is not equal with transaction nonce %s", value.getNonce(), input.getNonce()));
        }
    }
}
