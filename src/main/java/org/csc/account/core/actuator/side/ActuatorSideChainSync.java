package org.csc.account.core.actuator.side;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.csc.account.api.IAccountHelper;
import org.csc.account.api.IStateTrie;
import org.csc.account.api.ITransactionActuator;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.core.actuator.AbstractTransactionActuator;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.TransactionParameterInvalidException;
import org.csc.account.exception.TransactionVerifyException;
import org.csc.account.processor.BlockChainConfig;
import org.csc.account.util.ByteUtil;
import org.csc.bcapi.EncAPI;
import org.csc.evmapi.gens.Act.*;
import org.csc.evmapi.gens.Act.Account.Builder;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx;
import org.csc.evmapi.gens.Tx.SideChainData;
import org.csc.evmapi.gens.Tx.SideChainTxData;
import org.csc.evmapi.gens.Tx.Transaction;

import java.math.BigInteger;
import java.util.Map;

import static org.csc.account.processor.BlockChainConfig.isDisableEC;

/**
 * 子链数据同步交易，必须是联合账户
 *
 * @author liubo
 */
@Slf4j
public class ActuatorSideChainSync extends AbstractTransactionActuator implements ITransactionActuator {

    public ActuatorSideChainSync(IAccountHelper oAccountHelper, ITransactionHelper oTransactionHelper,
                                 BlockEntity oBlock, EncAPI encApi, IStateTrie oStateTrie) {
        super(oAccountHelper, oTransactionHelper, oBlock, encApi, oStateTrie);
    }

    private String signAddress = "";

    @Override
    public void onVerifySignature(Transaction tx, Map<ByteString, Builder> accounts) throws BlockException {
        Tx.TransactionInput oInput = tx.getBody().getInput();
        // 不在同一个分片上面, 不需要执行
        if (oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(oInput.getSliceId())) {
            return;
        }
        //关闭验签时不执行地址和签名检查
        if (isDisableEC) {
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
            if (!encApi.ecVerify(pubKey, oMultiTransactionEncode, tx.getBody().getSignatures().toByteArray())) {
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

        signAddress = hexAddress;
    }

    @Override
    public ByteString onExecute(Transaction tx, Map<ByteString, Builder> accounts) throws BlockException {
        Tx.TransactionInput oInput = tx.getBody().getInput();
        if (oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(oInput.getSliceId())) {
            return ByteString.EMPTY;
        }
        Account.Builder unionAccount = accounts.get(oInput.getAddress());

        // 执行sync
        try {
            SideChainData oSideChainData = SideChainData.parseFrom(tx.getBody().getData());
            SideChainStorage.Builder oSideChainStorage = SideChainStorage.newBuilder();
            oSideChainStorage.setBlockHash(oSideChainData.getHash());
            oSideChainStorage.setNumber(oSideChainData.getNumber());
            oSideChainStorage.setSourceTxHash(tx.getHash());

            for (SideChainTxData oSideChainTxData : oSideChainData.getSideChainTxList()) {
                SideChainTxStorage.Builder oSideChainTxStorage = SideChainTxStorage.newBuilder();
                oSideChainTxStorage.setI(oSideChainTxData.getI());
                oSideChainTxStorage.setStatus(oSideChainTxData.getStatus());
                oSideChainTxStorage.setTxHash(oSideChainTxData.getTxHash());
                oSideChainTxStorage.setResult(oSideChainTxData.getResult());
                oSideChainStorage.addTxs(oSideChainTxStorage);
            }
            byte[] exists = oAccountHelper.getStorage(unionAccount, oSideChainData.getHash().toByteArray());
            oAccountHelper.putStorage(unionAccount, oSideChainData.getHash().toByteArray(),
                    oSideChainStorage.build().toByteArray());

            AccountValue.Builder unionAccountValue = unionAccount.getValue().toBuilder();
            unionAccountValue.setNonce(oInput.getNonce() + 1);
            unionAccount.setValue(unionAccountValue);

            accounts.put(unionAccount.getAddress(), unionAccount);

            Account.Builder oSideChainRecordAccount = accounts
                    .get(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.sidechain_record_account_address)));
            byte[] recordStorage = oAccountHelper.getStorage(oSideChainRecordAccount,
                    oInput.getAddress().toByteArray());

            SideChainValue.Builder oSideChainValue;
            if (recordStorage != null) {
                oSideChainValue = SideChainValue.parseFrom(recordStorage).toBuilder();
            } else {
                oSideChainValue = SideChainValue.newBuilder();
            }

            if (exists == null) {
                oSideChainValue.setTotalBlocks(oSideChainValue.getTotalBlocks() + 1);
            }

            oSideChainValue.setLastBlockHash(oSideChainData.getHash());
            oSideChainValue.setLastBlockNumber(oSideChainData.getNumber());
            oSideChainValue.setLastSender(ByteString.copyFrom(encApi.hexDec(signAddress)));
            oSideChainValue.setLastTimestamp(tx.getBody().getTimestamp());
            oAccountHelper.putStorage(oSideChainRecordAccount, oInput.getAddress().toByteArray(),
                    oSideChainValue.build().toByteArray());

            accounts.put(oSideChainRecordAccount.getAddress(), oSideChainRecordAccount);

        } catch (InvalidProtocolBufferException e) {
            throw new BlockException(e);
        }
        return ByteString.EMPTY;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.csc.account.core.actuator.AbstractTransactionActuator#
     * onPrepareExecute( org.csc.evmapi.gens.Tx.Transaction, java.util.Map)
     *
     * 默认都是一次确认
     */
    @Override
    public void onPrepareExecute(Tx.Transaction tx, Map<ByteString, Account.Builder> accounts) throws BlockException {
        if (tx.getBody().getInput() == null) {
            throw new TransactionParameterInvalidException("parameter invalid, inputs must be only one");
        }
        if (tx.getBody().getOutputsCount() != 0) {
            throw new TransactionParameterInvalidException("parameter invalid, outputs must be null");
        }

        Tx.TransactionInput oInput = tx.getBody().getInput();
        // 不在同一个分片上面, 不需要执行
        if (oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(oInput.getSliceId())) {
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

        if (amount.compareTo(BigInteger.ZERO) != 0) {
            throw new TransactionParameterInvalidException("parameter invalid, amount must be zero");
        }

        try {
            // 验证数据格式是否正确
            SideChainData.parseFrom(tx.getBody().getData());
        } catch (InvalidProtocolBufferException e) {
            // TODO Auto-generated catch block
            throw new BlockException(e);
        }

        // 校验是否是有效的子链账户
        Account.Builder oSideChainRecordAccount = accounts
                .get(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.sidechain_record_account_address)));
        byte[] recordStorage = oAccountHelper.getStorage(oSideChainRecordAccount, oInput.getAddress().toByteArray());
        if (recordStorage == null) {
            throw new TransactionParameterInvalidException("parameter invalid, invalid side-chain account");
        }
    }
}
