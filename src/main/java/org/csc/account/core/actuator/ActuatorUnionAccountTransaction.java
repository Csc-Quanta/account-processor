package org.csc.account.core.actuator;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.csc.account.api.IAccountHelper;
import org.csc.account.api.IStateTrie;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.TransactionParameterInvalidException;
import org.csc.account.exception.TransactionVerifyException;
import org.csc.account.util.ByteUtil;
import org.csc.account.util.DateTimeUtil;
import org.csc.account.util.FastByteComparisons;
import org.csc.bcapi.EncAPI;
import org.csc.evmapi.gens.Act.Account;
import org.csc.evmapi.gens.Act.Account.Builder;
import org.csc.evmapi.gens.Act.AccountValue;
import org.csc.evmapi.gens.Act.UnionAccountStorage;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx;

import java.math.BigInteger;
import java.util.Map;

import static org.csc.account.processor.BlockChainConfig.isDisableEC;

@Slf4j
public class ActuatorUnionAccountTransaction extends AbstractTransactionActuator {

    public ActuatorUnionAccountTransaction(IAccountHelper oAccountHelper, ITransactionHelper oTransactionHelper,
                                           BlockEntity oBlock, EncAPI encApi, IStateTrie oStateTrie) {
        super(oAccountHelper, oTransactionHelper, oBlock, encApi, oStateTrie);
    }

    @Override
    public void onPrepareExecute(Tx.Transaction tx, Map<ByteString, Account.Builder> accounts)
            throws BlockException {
        if (tx.getBody().getInput() == null || tx.getBody().getOutputsCount() != 1) {
            throw new TransactionParameterInvalidException("parameter invalid, inputs or outputs must be only one");
        }

        Tx.TransactionInput oInput = tx.getBody().getInput();
        //不在同一个分片上面, 不需要执行
        if(oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(oInput.getSliceId())){
            return;
        }
        Account.Builder unionAccount = accounts.get(oInput.getAddress());
        AccountValue.Builder unionAccountValue = unionAccount.getValue().toBuilder();
        int txNonce = oInput.getNonce();
        int nonce = unionAccountValue.getNonce();
        if (nonce > txNonce) {
            throw new TransactionParameterInvalidException(
                    String.format("sender nonce %s is not equal with transaction nonce %s", nonce, nonce));
        }

        BigInteger amount = ByteUtil.bytesToBigInteger(oInput.getAmount().toByteArray());
        BigInteger unionAccountBalance = ByteUtil.bytesToBigInteger(unionAccountValue.getBalance().toByteArray());
        BigInteger acceptMax = ByteUtil.bytesToBigInteger(unionAccountValue.getAcceptMax().toByteArray());
        BigInteger max = ByteUtil.bytesToBigInteger(unionAccountValue.getMaxTrans().toByteArray());

        if (amount.compareTo(BigInteger.ZERO) <= 0) {
            throw new TransactionParameterInvalidException("parameter invalid, amount invalidate");
        }

        if (amount.compareTo(unionAccountBalance) > 0) {
            throw new TransactionParameterInvalidException(
                    String.format("sender balance %s less than %s", unionAccountBalance, amount));
        }

        if (amount.compareTo(max) > 0 && max.compareTo(BigInteger.ZERO) > 0) {
            throw new TransactionParameterInvalidException("parameter invalid, amount must small than " + max);
        }

        if (!FastByteComparisons.equal(oInput.getAmount().toByteArray(),
                tx.getBody().getOutputs(0).getAmount().toByteArray())) {
            throw new TransactionParameterInvalidException("parameter invalid, transaction value not equal");
        }

        if ((amount.compareTo(acceptMax) >= 0 && acceptMax.compareTo(BigInteger.ZERO) > 0)
                || acceptMax.compareTo(BigInteger.ZERO) == 0) {
            if (tx.getBody().getData() != null && !tx.getBody().getData().isEmpty()) {

                Tx.Transaction originalTx = oTransactionHelper.GetTransaction(tx.getBody().getData());
                if (originalTx == null) {
                    throw new TransactionParameterInvalidException("parameter invalid, not found original transaction");
                }

                if (!FastByteComparisons.equal(originalTx.getBody().getOutputs(0).getAddress().toByteArray(),
                        tx.getBody().getOutputs(0).getAddress().toByteArray())) {
                    throw new TransactionParameterInvalidException(
                            "parameter invalid, output address are equal with original tx");
                }

                byte[] confirmTxBytes = oAccountHelper.getStorage(unionAccount,
                        tx.getBody().getData().toByteArray());
                UnionAccountStorage oUnionAccountStorage;
                try {
                    oUnionAccountStorage = UnionAccountStorage.parseFrom(confirmTxBytes);
                } catch (InvalidProtocolBufferException e) {
                    throw new BlockException(e);
                }

                boolean isAlreadyConfirm = false;
                boolean isExistsConfirmTx = false;
                for (int i = 0; i < oUnionAccountStorage.getAddressCount(); i++) {
                    if (FastByteComparisons.equal(oUnionAccountStorage.getAddress(i).toByteArray(),
                            tx.getBody().getExtData().toByteArray())) {
                        isAlreadyConfirm = true;
                        break;
                    }
                    if (oUnionAccountStorage.getTxHash(i)
                            .equals(tx.getBody().getData())) {
                        isExistsConfirmTx = true;
                    }
                }
                if (isAlreadyConfirm) {
                    throw new TransactionParameterInvalidException(
                            "parameter invalid, transaction already confirmed by address "
                                    + encApi.hexEnc(tx.getBody().getExtData().toByteArray()));
                }
                if (!isExistsConfirmTx) {
                    throw new TransactionParameterInvalidException(
                            "parameter invalid, not found transaction need to be confirmed");
                }
                if (!FastByteComparisons.equal(originalTx.getBody().getInput().getAmount().toByteArray(),
                        oInput.getAmount().toByteArray())) {
                    throw new TransactionParameterInvalidException(
                            "parameter invalid, transaction amount not equal with original transaction");
                }

                if (oUnionAccountStorage != null) {
                    if (oUnionAccountStorage.getAddressCount() >= unionAccountValue.getAcceptLimit()) {
                        throw new TransactionParameterInvalidException(
                                "parameter invalid, transaction already confirmed");
                    }
                }

                if (oUnionAccountStorage.getAddressCount() + 1 == unionAccountValue.getAcceptLimit()) {
                    if (DateTimeUtil.isToday(unionAccountValue.getAccumulatedTimestamp(),
                            tx.getBody().getTimestamp())) {
                        BigInteger totalMax = ByteUtil
                                .bytesToBigInteger(unionAccountValue.getAccumulated().toByteArray());
                        if (amount.add(totalMax).compareTo(max) > 0 && max.compareTo(BigInteger.ZERO) > 0) {
                            throw new TransactionParameterInvalidException(
                                    "parameter invalid, already more than the maximum transfer amount of the day");
                        }
                    }
                }
            } else {
                BigInteger totalMax = ByteUtil.bytesToBigInteger(unionAccountValue.getAccumulated().toByteArray());
                if (amount.add(totalMax).compareTo(max) > 0 && max.compareTo(BigInteger.ZERO) > 0) {
                    throw new TransactionParameterInvalidException(
                            "parameter invalid, already more than the maximum transfer amount of the day");
                }
            }
        } else {
            BigInteger totalMax = ByteUtil.bytesToBigInteger(unionAccountValue.getAccumulated().toByteArray());
            if (amount.add(totalMax).compareTo(max) > 0 && max.compareTo(BigInteger.ZERO) > 0) {
                throw new TransactionParameterInvalidException(
                        "parameter invalid, already more than the maximum transfer amount of the day");
            }
        }
    }

    @Override
    public ByteString onExecute(Tx.Transaction tx, Map<ByteString, Builder> accounts)
            throws BlockException {
        Tx.TransactionInput oInput = tx.getBody().getInput();
        //不在同一个分片上面, 不需要执行
        if(oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(oInput.getSliceId())){
            return ByteString.EMPTY;
        }
        Account.Builder unionAccount = accounts.get(oInput.getAddress());
        AccountValue.Builder unionAccountValue = unionAccount.getValue().toBuilder();

        BigInteger amount = ByteUtil.bytesToBigInteger(oInput.getAmount().toByteArray());
        BigInteger acceptMax = ByteUtil.bytesToBigInteger(unionAccountValue.getAcceptMax().toByteArray());

        if (amount.compareTo(acceptMax) >= 0) {
            if (tx.getBody().getData() == null || tx.getBody().getData().isEmpty()) {
                // first commit
                unionAccountValue.setNonce(oInput.getNonce() + 1);
                unionAccount.setValue(unionAccountValue);

                UnionAccountStorage.Builder oUnionAccountStorage = UnionAccountStorage.newBuilder();
                oUnionAccountStorage.addAddress(tx.getBody().getExtData());
                oUnionAccountStorage.addTxHash(tx.getHash());

                oAccountHelper.putStorage(unionAccount, tx.getHash().toByteArray(),
                        oUnionAccountStorage.build().toByteArray());
                accounts.put(oInput.getAddress(), unionAccount);
                return ByteString.EMPTY;
            } else {
                byte[] confirmTxBytes = oAccountHelper.getStorage(unionAccount,
                        tx.getBody().getData().toByteArray());
                UnionAccountStorage.Builder oUnionAccountStorage;
                try {
                    oUnionAccountStorage = UnionAccountStorage.parseFrom(confirmTxBytes).toBuilder();
                } catch (InvalidProtocolBufferException e) {
                    throw new BlockException(e);
                }
                oUnionAccountStorage.addAddress(tx.getBody().getExtData());
                oUnionAccountStorage.addTxHash(tx.getHash());

                // log.debug("union account dump storage::" + oUnionAccountStorage.build());

                oAccountHelper.putStorage(unionAccount, tx.getBody().getData().toByteArray(),
                        oUnionAccountStorage.build().toByteArray());

                unionAccountValue = unionAccount.getValue().toBuilder();
                if (oUnionAccountStorage.getAddressCount() != unionAccountValue.getAcceptLimit()) {
                    // log.debug("union account[" + "]" + "need more confirm::"
                    // + (unionAccountValue.getAcceptLimit() -
                    // oUnionAccountStorage.getAddressCount()));
                    // need more confirm
                    unionAccountValue.setNonce(oInput.getNonce() + 1);
                    unionAccount.setValue(unionAccountValue);

                    accounts.put(oInput.getAddress(), unionAccount);
                    return ByteString.EMPTY;
                }
            }
        }
        unionAccountValue = unionAccount.getValue().toBuilder();
        if (DateTimeUtil.isToday(unionAccountValue.getAccumulatedTimestamp(),
                tx.getBody().getTimestamp())) {
            BigInteger accumulated = ByteUtil.bytesToBigInteger(unionAccountValue.getAccumulated().toByteArray());
            unionAccountValue.setAccumulated(ByteString.copyFrom(ByteUtil.bigIntegerToBytes(accumulated.add(amount))));
        } else {
            unionAccountValue.setAccumulated(ByteString.copyFrom(ByteUtil.bigIntegerToBytes(amount)));
        }
        unionAccountValue.setAccumulatedTimestamp(tx.getBody().getTimestamp());
        unionAccount.setValue(unionAccountValue);

        accounts.put(unionAccount.getAddress(), unionAccount);
        return super.onExecute(tx, accounts);
    }

    @Override
    public void onVerifySignature(Tx.Transaction tx, Map<ByteString, Account.Builder> accounts)
            throws BlockException {
        Tx.TransactionInput oInput = tx.getBody().getInput();
        //不在同一个分片上面, 不需要执行
        if(oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(oInput.getSliceId())){
            return;
        }
        //关闭校验时不执行验签和地址检查
        if(isDisableEC){
            return;
        }

        Account.Builder sender = accounts.get(oInput.getAddress());
        AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();

        Tx.Transaction.Builder signatureTx = tx.toBuilder();
        Tx.TransactionBody.Builder txBody = signatureTx.getBodyBuilder();
        signatureTx.clearHash();
        txBody = txBody.clearSignatures();
        byte[] oMultiTransactionEncode = txBody.build().toByteArray();
        byte[] pubKey = encApi.ecToKeyBytes(oMultiTransactionEncode,
                encApi.hexEnc(tx.getBody().getSignatures().toByteArray()));
        String hexAddress = encApi.ecToAddressHex(oMultiTransactionEncode,
                encApi.hexEnc(tx.getBody().getSignatures().toByteArray()));

        boolean isRelAddress = false;
        for (ByteString relAddress : senderAccountValue.getSubAddressList()) {
            if (hexAddress.equals(encApi.hexEnc(relAddress.toByteArray()))) {
                isRelAddress = true;
                break;
            }
        }
        if (isRelAddress) {
            if (!encApi.ecVerify(pubKey, oMultiTransactionEncode,
                    tx.getBody().getSignatures().toByteArray())) {
                throw new TransactionVerifyException(String.format("signature %s verify fail with pubkey %s",
                        encApi.hexEnc(tx.getBody().getSignatures().toByteArray()), encApi.hexEnc(pubKey)));
            }
        } else {
            throw new TransactionVerifyException(
                    "signature verify fail, current account are not allowed to initiate transactions");
        }

        if (!encApi.hexEnc(tx.getBody().getExtData().toByteArray()).equals(hexAddress)) {
            throw new TransactionVerifyException("signature verify fail, transaction data not equal with Signer");
        }
    }
}
