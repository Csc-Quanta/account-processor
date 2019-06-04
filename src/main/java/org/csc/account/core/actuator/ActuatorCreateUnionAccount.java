package org.csc.account.core.actuator;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.csc.account.api.IAccountHelper;
import org.csc.account.api.IStateTrie;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.TransactionExecuteException;
import org.csc.account.exception.TransactionParameterInvalidException;
import org.csc.account.util.ByteUtil;
import org.csc.bcapi.EncAPI;
import org.csc.evmapi.gens.Act.Account;
import org.csc.evmapi.gens.Act.Account.Builder;
import org.csc.evmapi.gens.Act.AccountValue;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx;
import org.csc.evmapi.gens.Tx.UnionAccountData;

import java.math.BigInteger;
import java.util.Map;

/**
 * 创建联合账户
 *
 * @author lance
 * @since 2019.1.14 16:01
 */
public class ActuatorCreateUnionAccount extends AbstractTransactionActuator {

    public ActuatorCreateUnionAccount(IAccountHelper oAccountHelper, ITransactionHelper oTransactionHelper,
                                      BlockEntity oBlock, EncAPI encApi, IStateTrie oStateTrie) {
        super(oAccountHelper, oTransactionHelper, oBlock, encApi, oStateTrie);
    }

    @Override
    public void onPrepareExecute(Tx.Transaction tx, Map<ByteString, Account.Builder> accounts)
            throws BlockException {
        if (tx.getBody().getInput() == null) {
            throw new TransactionParameterInvalidException("parameter invalid, inputs must be only one");
        }

        Tx.TransactionInput oInput = tx.getBody().getInput();
        //不在同一个分片上面, 不需要执行
		/*if(oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(oInput.getSliceId())){
			return;
		}*/

        if (ByteUtil.bytesToBigInteger(oInput.getAmount().toByteArray()).compareTo(BigInteger.ZERO) != 0) {
            throw new TransactionParameterInvalidException("parameter invalid, amount must be zero");
        }

        if (tx.getBody().getOutputsCount() != 0) {
            throw new TransactionParameterInvalidException("parameter invalid, inputs must be empty");
        }

        if (tx.getBody().getSignatures().isEmpty()) {
            throw new TransactionParameterInvalidException(
                    "parameter invalid, inputs count must equal with signatures count");
        }

        Account.Builder sender = accounts.get(oInput.getAddress());
        AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();
        int txNonce = oInput.getNonce();
        int nonce = senderAccountValue.getNonce();
        if (nonce > txNonce) {
            throw new TransactionParameterInvalidException(
                    String.format("parameter invalid, sender nonce %s is not equal with transaction nonce %s", nonce, nonce));
        }

        UnionAccountData oUnionAccountData = null;
        try {
            oUnionAccountData = UnionAccountData.parseFrom(tx.getBody().getData().toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new BlockException(e);
        }
        if (oUnionAccountData == null || oUnionAccountData.getMaxTrans() == null || oUnionAccountData.getAcceptLimit() < 0
                || oUnionAccountData.getAcceptMax() == null || oUnionAccountData.getAddressCount() < 2) {
            throw new TransactionParameterInvalidException("parameter invalid, union account info invalidate");
        }

        if (oUnionAccountData.getAcceptLimit() > oUnionAccountData.getAddressCount()) {
            throw new TransactionParameterInvalidException(
                    "parameter invalid, AcceptLimit count must smaller than address count");
        }
    }

    @Override
    public ByteString onExecute(Tx.Transaction tx, Map<ByteString, Builder> accounts) throws BlockException {
        Tx.TransactionInput oInput = tx.getBody().getInput();
        //不在同一个分片上面, 不需要执行
        /*if(oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(oInput.getSliceId())){
            return ByteString.EMPTY;
        }*/

        Account.Builder sender = accounts.get(oInput.getAddress());
        AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();

        senderAccountValue.setNonce(oInput.getNonce() + 1);
        sender.setValue(senderAccountValue);
        accounts.put(sender.getAddress(), sender);

        UnionAccountData oUnionAccountData = null;
        try {
            oUnionAccountData = UnionAccountData.parseFrom(tx.getBody().getData().toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new BlockException(e);
        }

        try {
            //修改联合账号地址
            ByteString address = oTransactionHelper.getContractAddressByTransaction(tx);
            if (oAccountHelper.isExist(address)) {
                throw new TransactionExecuteException("UnionAccount address already exists");
            }

            Account.Builder oUnionAccount = oAccountHelper.createUnionAccount(address, oUnionAccountData.getMaxTrans(),
                    oUnionAccountData.getAcceptMax(), oUnionAccountData.getAcceptLimit(),
                    oUnionAccountData.getAddressList());
            accounts.put(address, oUnionAccount);
            return address;

        } catch (Exception e) {
            throw new BlockException(e);
        }
    }
}
