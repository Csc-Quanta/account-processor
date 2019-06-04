package org.csc.account.core.actuator;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.csc.account.api.IAccountHelper;
import org.csc.account.api.IStateTrie;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.TransactionParameterInvalidException;
import org.csc.account.processor.BlockChainConfig;
import org.csc.account.util.ByteUtil;
import org.csc.account.util.FastByteComparisons;
import org.csc.bcapi.EncAPI;
import org.csc.evmapi.gens.Act.*;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx;
import org.csc.evmapi.gens.Tx.CryptoTokenData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ERC721创建, 目前扣除手续费按照20规则, 需要业务规则定义, 每次创建token数量消耗不一致
 *
 * @author lance
 * @since 2019.1.9 21:53
 */
@Slf4j
public class ActuatorCreateCryptoToken extends AbstractTransactionActuator  {

    public ActuatorCreateCryptoToken(IAccountHelper oAccountHelper, ITransactionHelper oTransactionHelper,
                                     BlockEntity oBlock, EncAPI encApi, IStateTrie oStateTrie) {
        super(oAccountHelper, oTransactionHelper, oBlock, encApi, oStateTrie);
    }

    /**
     * 不校验发送方和接收方的balance的一致性
     *
     * @param tx
     * @param accounts
     * @throws Exception
     */
    @Override
    public void onPrepareExecute(Tx.Transaction tx, Map<ByteString, Account.Builder> accounts)
            throws BlockException {
        if (tx.getBody().getInput() == null) {
            throw new TransactionParameterInvalidException("parameter invalid, inputs must be only one");
        }

        try {
            CryptoTokenData cryptTokenData = CryptoTokenData.parseFrom(tx.getBody().getData().toByteArray());
            if (cryptTokenData.getCodeCount() != cryptTokenData.getNameCount()
                    || cryptTokenData.getCodeCount() == 0 || cryptTokenData.getTotal() == 0) {
                throw new TransactionParameterInvalidException(
                        "parameter invalid, crypto token count must large than 0");
            }

            String symbol = cryptTokenData.getSymbol().toStringUtf8();
            if (cryptTokenData.getSymbol().isEmpty()) {
    			throw new TransactionParameterInvalidException("parameter invalid, crypto token symbol must not be null");
    		} else if (!symbol.toUpperCase().equals(cryptTokenData.getSymbol().toStringUtf8())) {
    			throw new TransactionParameterInvalidException("parameter invalid, crypto token symbol invalid");
    		} else if (symbol.startsWith("CSC")) {
    			throw new TransactionParameterInvalidException(
    					"parameter invalid, crypto token symbol cannot has CSC prefix");
    		} else if (cryptTokenData.getSymbol().size() > 8) {
    			throw new TransactionParameterInvalidException("parameter invalid, crypto token symbol too long");
    		}

            //验证分片
            Tx.TransactionInput input = tx.getBody().getInput();
            if (oAccountHelper.canExecute(input.getSliceId())) {
            	Account.Builder cryptoRecordAccount = accounts
        				.get(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.cryptotoken_record_account_address)));
        		CryptoTokenOrigin.Builder oCryptoTokenOrigin = oAccountHelper.getCryptoBySymbol(cryptoRecordAccount.build(),
						cryptTokenData.getSymbol().toByteArray());

        		if (oCryptoTokenOrigin == null || oCryptoTokenOrigin.getOriginValueCount() == 0) {
        			oCryptoTokenOrigin = CryptoTokenOrigin.newBuilder();
        		} else {
        			// 取最后一次的token信息
        			CryptoTokenOriginValue oCryptoTokenOriginValue = oCryptoTokenOrigin
        					.getOriginValue(oCryptoTokenOrigin.getOriginValueCount() - 1);

        			if (!FastByteComparisons.equal(oCryptoTokenOriginValue.getOwner().toByteArray(),
							tx.getBody().getInput().getAddress().toByteArray())) {
        				throw new TransactionParameterInvalidException(
        						"parameter invalid, cannot create crypto token with address "
        								+ encApi.hexEnc(tx.getBody().getInput().getAddress().toByteArray()));
        			} else if ((oCryptoTokenOriginValue.getEndIndex()
        					+ cryptTokenData.getNameCount()) > oCryptoTokenOriginValue.getTotal()) {
        				throw new TransactionParameterInvalidException("parameter invalid, too many crypto token");
        			}
        			
        			if (cryptTokenData.getTotal() != oCryptoTokenOriginValue.getTotal()) {
        				throw new TransactionParameterInvalidException("parameter invalid, transaction data invalid");
        			}
        		}
            }
        } catch (InvalidProtocolBufferException e) {
            throw new BlockException(e);
        }

        super.onPrepareExecute(tx, accounts);
    }

    @Override
    public ByteString onExecute(Tx.Transaction tx, Map<ByteString, Account.Builder> accounts)throws BlockException {
        CryptoTokenData cryptTokenData;
        try {
			cryptTokenData = CryptoTokenData.parseFrom(tx.getBody().getData().toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new BlockException(e);
        }
        //验证分片
        Tx.TransactionInput input = tx.getBody().getInput();
        //不在同一个分片上面, 不需要执行
        if (oAccountHelper.getSliceTotal() > 0 && !oAccountHelper.validSlice(input.getSliceId())) {
            return ByteString.EMPTY;
        }

        Account.Builder sender = accounts.get(input.getAddress());
        AccountValue.Builder senderAccountValue = sender.getValue().toBuilder();
		//ERC721创建Token, 账户余额前后变化
        if(log.isDebugEnabled()){
			String balance = String.valueOf(ByteUtil.bytesToBigInteger(senderAccountValue.getBalance().toByteArray()));
			String amount = String.valueOf(ByteUtil.bytesToBigInteger(input.getAmount().toByteArray()));
        	log.debug("===>Origin Account balance: {}, amount: {}", balance, amount);
		}
		//创建ERC721扣除手续费, 目前按照固定金额扣除, 后续需要按照token数量进行扣除, 待业务规则定义
        senderAccountValue.setNonce(input.getNonce() + 1);
        senderAccountValue.setBalance(ByteString.copyFrom(ByteUtil
				.bigIntegerToBytes(ByteUtil.bytesToBigInteger(senderAccountValue.getBalance().toByteArray())
						.subtract(BlockChainConfig.token_lock_balance))));

        //ERC721创建Token, 账户余额前后变化
		if(log.isDebugEnabled()){
			String balance = String.valueOf(ByteUtil.bytesToBigInteger(senderAccountValue.getBalance().toByteArray()));
			log.debug("===>Tx after balance: {}", balance);
		}
		Account.Builder cryptoRecordAccount = accounts.get(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.cryptotoken_record_account_address)));
		CryptoTokenOrigin.Builder oCryptoTokenOrigin = oAccountHelper.getCryptoBySymbol(cryptoRecordAccount.build(),
				cryptTokenData.getSymbol().toByteArray());

		if (oCryptoTokenOrigin == null) {
			oCryptoTokenOrigin = CryptoTokenOrigin.newBuilder();
		}
		
		CryptoTokenOriginValue.Builder oCryptoTokenOriginValue = CryptoTokenOriginValue.newBuilder();
		oCryptoTokenOriginValue.setOwner(input.getAddress());
		if (oCryptoTokenOrigin.getOriginValueCount() > 0) {
			oCryptoTokenOriginValue.setStartIndex(
					oCryptoTokenOrigin.getOriginValue(oCryptoTokenOrigin.getOriginValueCount() - 1).getEndIndex());
		} else {
			oCryptoTokenOriginValue.setStartIndex(0);
		}
		oCryptoTokenOriginValue.setEndIndex(oCryptoTokenOriginValue.getStartIndex() + cryptTokenData.getNameCount());
		oCryptoTokenOriginValue.setSymbol(cryptTokenData.getSymbol());
		oCryptoTokenOriginValue.setTotal(cryptTokenData.getTotal());
		oCryptoTokenOriginValue.setExtData(cryptTokenData.getExtData());
		oCryptoTokenOriginValue.setTimestamp(tx.getBody().getTimestamp());
		oCryptoTokenOriginValue.setTxHash(encApi.hexEnc(tx.getHash().toByteArray()));

		ByteString cryptoAccountAddress = ByteString.copyFrom(encApi.sha3Encode(cryptTokenData.getSymbol().toByteArray()));
		Account.Builder cryptoAccount = accounts.get(cryptoAccountAddress);
		if (cryptoAccount == null) {
			cryptoAccount = oAccountHelper.getAccountOrCreate(cryptoAccountAddress);
		}

        List<AccountCryptoToken> tokens = new ArrayList<>();
		for (int i = 0; i < cryptTokenData.getNameCount(); i++) {
			AccountCryptoToken.Builder oAccountCryptoToken = AccountCryptoToken.newBuilder();
			oAccountCryptoToken.setCode(cryptTokenData.getCode(i));
			oAccountCryptoToken.setExtData(cryptTokenData.getExtData());
			oAccountCryptoToken.setIndex(oCryptoTokenOriginValue.getStartIndex() + i);
			oAccountCryptoToken.setName(cryptTokenData.getName(i));
			oAccountCryptoToken.setNonce(0);
			oAccountCryptoToken.setOwner(input.getAddress());
			oAccountCryptoToken.setOwnertime(tx.getBody().getTimestamp());
			oAccountCryptoToken.setTotal(oCryptoTokenOriginValue.getTotal());
			oAccountCryptoToken.setTimestamp(tx.getBody().getTimestamp());
			oAccountCryptoToken.setSymbol(cryptTokenData.getSymbol());
			oAccountCryptoToken.clearHash();
			oAccountCryptoToken
					.setHash(ByteString.copyFrom(encApi.sha256Encode(oAccountCryptoToken.build().toByteArray())));

			oCryptoTokenOriginValue.addName(cryptTokenData.getName(i));
			oCryptoTokenOriginValue.addCode(cryptTokenData.getCode(i));
			tokens.add(oAccountCryptoToken.build());

			oAccountHelper.putStorage(cryptoAccount, oAccountCryptoToken.getHash().toByteArray(),
					oAccountCryptoToken.build().toByteArray());
		}

		oCryptoTokenOrigin.addOriginValue(oCryptoTokenOriginValue);
		accounts.put(sender.getAddress(), sender);
		oAccountHelper.putStorage(cryptoRecordAccount, cryptTokenData.getSymbol().toByteArray(),
				oCryptoTokenOrigin.build().toByteArray());

		// 记录owner和token的关系
		boolean isExistsCryptoSymbol = false;
		for (int j = 0; j < senderAccountValue.getCryptosCount(); j++) {
			if (senderAccountValue.getCryptos(j).getSymbol().equals(cryptTokenData.getSymbol())) {
				isExistsCryptoSymbol = true;
				AccountCryptoValue.Builder oAccountCryptoValue = senderAccountValue.getCryptos(j).toBuilder();
				for (AccountCryptoToken accountCryptoToken : tokens) {
					oAccountCryptoValue.addTokens(accountCryptoToken);
				}
				senderAccountValue.setCryptos(j, oAccountCryptoValue);
				break;
			}
		}
		if (!isExistsCryptoSymbol) {
			AccountCryptoValue.Builder oAccountCryptoValue = AccountCryptoValue.newBuilder();
			oAccountCryptoValue.setSymbol(cryptTokenData.getSymbol());
			for (AccountCryptoToken accountCryptoToken : tokens) {
				oAccountCryptoValue.addTokens(accountCryptoToken);
			}
			senderAccountValue.addCryptos(oAccountCryptoValue);
		}
		sender.setValue(senderAccountValue);

		//创建ERC721扣除手续费, 目前按照固定金额扣除, 后续需要按照token数量进行扣除, 待业务规则定义
		Account.Builder locker = accounts.get(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.lock_account_address)));
		AccountValue.Builder lockerAccountValue = locker.getValue().toBuilder();
		lockerAccountValue.setBalance(ByteString.copyFrom(
				ByteUtil.bigIntegerToBytes(ByteUtil.bytesToBigInteger(lockerAccountValue.getBalance().toByteArray())
						.add(BlockChainConfig.token_lock_balance))));
		locker.setValue(lockerAccountValue);

		accounts.put(sender.getAddress(), sender);
		accounts.put(cryptoAccount.getAddress(), cryptoAccount);
		accounts.put(cryptoRecordAccount.getAddress(), cryptoRecordAccount);
		accounts.put(locker.getAddress(), locker);
		return ByteString.EMPTY;
    }
}
