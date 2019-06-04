package org.csc.account.core.actuator;

import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.csc.account.api.IAccountHelper;
import org.csc.account.api.IStateTrie;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.TransactionParameterInvalidException;
import org.csc.account.util.ByteUtil;
import org.csc.bcapi.EncAPI;
import org.csc.evmapi.gens.Act;
import org.csc.evmapi.gens.Act.Account;
import org.csc.evmapi.gens.Act.AccountCryptoToken;
import org.csc.evmapi.gens.Act.AccountCryptoValue;
import org.csc.evmapi.gens.Act.AccountValue;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ERC721交易
 *
 * @author lance
 * @since 2019.1.10 09:23
 */
@Slf4j
public class ActuatorCryptoTokenTransaction extends AbstractTransactionActuator {

    public ActuatorCryptoTokenTransaction(IAccountHelper oAccountHelper, ITransactionHelper oTransactionHelper,
                                          BlockEntity oBlock, EncAPI encApi, IStateTrie oStateTrie) {
        super(oAccountHelper, oTransactionHelper, oBlock, encApi, oStateTrie);
    }

    /**
     * 不校验发送方和接收方的balance的一致性
     *
     * @param tx                交易請求
     * @param accounts          账户
     * @throws Exception
     */
    @Override
    public void onPrepareExecute(Tx.Transaction tx, Map<ByteString, Account.Builder> accounts)
            throws BlockException {
        Tx.TransactionInput input = tx.getBody().getInput();
        if(input == null){
            throw new TransactionParameterInvalidException("parameter invalid, sender address must be exist");
        }

        //发送者ERC721 token数量
        if(input.getCryptoTokenCount() == 0){
            throw new TransactionParameterInvalidException("parameter invalid, input cryptToken equals 0");
        }

        //发起的Token集合
        List<ByteString> inTokens = input.getCryptoTokenList();
        List<ByteString> unique = inTokens.stream().distinct().collect(Collectors.toList());
        if(inTokens.size() != unique.size()){
            throw new TransactionParameterInvalidException("parameter invalid, duplicate token");
        }

        //不在同一个分片上面, 不需要执行
        if (oAccountHelper.canExecute(input.getSliceId())) {
            ByteString address = input.getAddress();
            Account.Builder account = accounts.get(address);
            AccountValue accountValue = account.getValue();

            boolean isSymbol = accountValue.getCryptosList().stream()
                    .anyMatch(t-> Objects.equals(t.getSymbol(), input.getSymbol()));

            //验证721 Symbol是否存在个人账户
            if(!isSymbol){
                throw new TransactionParameterInvalidException("parameter invalid, input symbol not exist");
            }

            //验证ERC721是否存在
            inTokens.forEach(t -> {
                boolean exist = accountValue.getCryptosList().stream().anyMatch(c->
                    c.getTokensList().stream().anyMatch(i->Objects.equals(i.getHash(), t))
                );

                if(!exist){
                    throw new TransactionParameterInvalidException(
                            String.format("parameter invalid, input account %s not found token [%s] with hash [%s]",
                                    encApi.hexEnc(input.getAddress().toByteArray()), encApi.hexEnc(input.getSymbol().toByteArray()),
                                    encApi.hexEnc(t.toByteArray())));
                }
            });
        }

        //验证Output中Token.hash是否和input一致
        tx.getBody().getOutputsList().forEach(o->
            o.getCryptoTokenList().forEach(t -> {
                boolean isExistInput = inTokens.stream().anyMatch(i -> Objects.equals(i, t));
                if(!isExistInput){
                    throw new TransactionParameterInvalidException(
                            format("parameter invalid, not found token %s with hash %s in input list",
                            		encApi.hexEnc(input.getSymbol().toByteArray()), encApi.hexEnc(t.toByteArray())));
                }
            })
        );

        super.onPrepareExecute(tx, accounts);
    }

    @Override
    public ByteString onExecute(Tx.Transaction tx, Map<ByteString, Account.Builder> accounts)throws BlockException {
        Map<ByteString, AccountCryptoToken> tokens = new HashMap<>();

        Tx.TransactionInput input = tx.getBody().getInput();
        //if (oAccountHelper.canExecute(input.getSliceId())) {
        //Input账户余额发现变化
        Account.Builder sender = accounts.get(input.getAddress());
        AccountValue.Builder accountValue = sender.getValue().toBuilder();
        accountValue.setBalance(ByteString.copyFrom(ByteUtil
                .bytesSubToBytes(accountValue.getBalance().toByteArray(), input.getAmount().toByteArray())));

        for (int k = 0; k < accountValue.getCryptosCount(); k++) {
            if (accountValue.getCryptosList().get(k).getSymbol().equals(input.getSymbol())) {
                AccountCryptoValue.Builder value = accountValue.getCryptosList().get(k).toBuilder();

                for (int j = 0; j < value.getTokensCount(); j++) {

                    if(input.getCryptoTokenList().contains(value.getTokensBuilderList().get(j).getHash())){
                        tokens.put(value.getTokensBuilderList().get(j).getHash(), value.getTokensBuilderList().get(j).build());
                        value.removeTokens(j);
                        break;
                    }
                }
                accountValue.setCryptos(k, value);
                break;
            }
        }

        accountValue.setNonce(input.getNonce() + 1);
        sender.setValue(accountValue);
        accounts.put(sender.getAddress(), sender);
        //}

        // 接收方增加balance
        for (int i = 0; i < tx.getBody().getOutputsCount(); i++) {
            Tx.TransactionOutput output = tx.getBody().getOutputs(i);
            //不在同一个分片上面, 不需要执行
            /*if (oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(output.getSliceId())) {
                continue;
            }*/

            Account.Builder account = accounts.get(output.getAddress());
            if (account == null) {
                account = oAccountHelper.createAccount(output.getAddress());
            }
            AccountValue.Builder value = account.getValue().toBuilder();

            value.setBalance(ByteString.copyFrom(ByteUtil
                    .bigIntegerToBytes(ByteUtil.bytesToBigInteger(value.getBalance().toByteArray())
                            .add(ByteUtil.bytesToBigInteger(output.getAmount().toByteArray())))));

            Account.Builder cryptAccount = oAccountHelper.getAccountOrCreate(ByteString.copyFrom(encApi.sha3Encode(input.getSymbol().toByteArray())));
            AccountCryptoValue.Builder cryptValue = value.getCryptosList().stream()
                    .filter(t->Objects.equals(t.getSymbol(), input.getSymbol()))
                    .findFirst()
                    .orElse(AccountCryptoValue.newBuilder().setSymbol(input.getSymbol()).build()).toBuilder();

            output.getCryptoTokenList().forEach(t->{
                AccountCryptoToken.Builder cryptToken = tokens.get(t).toBuilder();
                cryptToken.setOwner(output.getAddress());
                cryptToken.setNonce(cryptToken.getNonce() + 1);
                cryptToken.setOwnertime(tx.getBody().getTimestamp());
                cryptValue.addTokens(cryptToken.build());
                value.addCryptos(cryptValue);

                oAccountHelper.putStorage(cryptAccount, cryptToken.getHash().toByteArray(),cryptToken.build().toByteArray());
            });

            account.setValue(value);
            accounts.put(account.getAddress(), account);
            accounts.put(cryptAccount.getAddress(), cryptAccount);
        }

        return ByteString.EMPTY;
    }
}
