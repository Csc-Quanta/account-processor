package org.csc.account.processor.evmapi;

import com.google.protobuf.ByteString;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.csc.account.api.*;
import org.csc.account.processor.BlockChainConfig;
import org.csc.account.util.ByteUtil;
import org.csc.account.util.FastByteComparisons;
import org.csc.bcapi.EncAPI;
import org.csc.evm.api.EvmApi;
import org.csc.evmapi.gens.Act.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Slf4j
public class EvmApiImp implements EvmApi {
	IAccountHelper accountHelper;
	IChainHelper blockChainHelper;
	ITransactionHelper transactionHelper;
	EncAPI encApi;
	IStorageTrieCache storageTrieCache;

	Map<String, Account> touchAccount = new HashMap<>();
	Map<ByteString, Account.Builder> memoryAccount = new HashMap<>();

	private Account.Builder getAccount(ByteString addr) {
		if (!touchAccount.containsKey(encApi.hexEnc(addr.toByteArray()))) {
			if (!memoryAccount.containsKey(addr))
				return accountHelper.getAccountOrCreate(addr);
			else
				return memoryAccount.get(addr);
		} else {
			return touchAccount.get(encApi.hexEnc(addr.toByteArray())).toBuilder();
		}
	}

	@Override
	public Account GetAccount(ByteString addr) {
		return getAccount(addr).build();
	}

	@Override
	public BigInteger addBalance(ByteString addr, BigInteger balance) {
		try {
			Account.Builder oAccount = getAccount(addr);
			if (oAccount == null) {

			} else {
				AccountValue.Builder value = oAccount.getValue().toBuilder();
				value.setBalance(
						ByteString.copyFrom(ByteUtil.bytesAddToBytes(balance, value.getBalance().toByteArray())));

				oAccount.setValue(value);
			}
			touchAccount.put(encApi.hexEnc(addr.toByteArray()), oAccount.build());
			return ByteUtil.bytesToBigInteger(oAccount.getValue().getBalance().toByteArray());
		} catch (Exception e) {
			e.printStackTrace();
		}
		return BigInteger.ZERO;
	}

	@Override
	public BigInteger getBalance(ByteString addr) {
		try {
			return ByteUtil.bytesToBigInteger(getAccount(addr).getValue().getBalance().toByteArray());
		} catch (Exception e) {
			e.printStackTrace();
		}
		return BigInteger.ZERO;
	}

	@Override
	public boolean isExist(ByteString addr) {
		try {
			return accountHelper.isExist(addr);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}

	@Override
	public void saveStorage(ByteString address, byte[] key, byte[] value) {
		// accountHelper.saveStorage(address, key, value);
		try {
			Account.Builder oAccount = getAccount(address);
			if (oAccount != null) {
				AccountValue.Builder oAccountValue = oAccount.getValue().toBuilder();
				IStorageTrie oStorage = accountHelper.getStorageTrie(oAccount);
				oStorage.put(key, value);
				byte[] rootHash = oStorage.getRootHash();
				oAccountValue.setStorage(ByteString.copyFrom(rootHash));
				oAccount.setValue(oAccountValue);
				touchAccount.put(encApi.hexEnc(address.toByteArray()), oAccount.build());

				log.debug("storage save::" + " address::" + encApi.hexEnc(address.toByteArray()) + "key::"
						+ encApi.hexEnc(key) + " value::" + encApi.hexEnc(value));
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public Map<String, byte[]> getStorage(ByteString address, List<byte[]> keys) {
		Map<String, byte[]> storage = new HashMap<>();
		Account.Builder oAccount = getAccount(address);
		IStorageTrie oStorage = accountHelper.getStorageTrie(oAccount);
		for (int i = 0; i < keys.size(); i++) {
			String keyStr = encApi.hexEnc(keys.get(i));
			String valueStr = "null";
			try {
				if (oStorage.get(keys.get(i)) == null) {

				} else {
					valueStr = encApi.hexEnc(oStorage.get(keys.get(i)));
				}
			} catch (Exception e) {
				// TODO: handle exception
			}
			log.debug("storage get::" + " address::" + encApi.hexEnc(address.toByteArray()) + "key::" + keyStr
					+ " value::" + valueStr);

			storage.put(encApi.hexEnc(keys.get(i)), oStorage.get(keys.get(i)));
		}
		return storage;
	}

	@Override
	public byte[] getStorage(ByteString address, byte[] key) {
		Account.Builder oAccount = getAccount(address);
		IStorageTrie oStorage = accountHelper.getStorageTrie(oAccount);

//		String keyStr = encApi.hexEnc(key);
		String valueStr = "null";

		try {
			if (oStorage.get(key) == null) {

			} else {
				valueStr = encApi.hexEnc(oStorage.get(key));
			}
		} catch (Exception e) {
			// TODO: handle exception
		}
		log.debug("storage get::" + " address::" + encApi.hexEnc(address.toByteArray()) + "key::" + encApi.hexEnc(key)
				+ " value::" + valueStr);
		return oStorage.get(key);
	}

	@Override
	public void addCryptoTokenBalance(ByteString address, byte[] symbol, AccountCryptoToken oAccountCryptoToken) {
		Account.Builder account = getAccount(address);
		AccountValue.Builder oAccountValue = account.getValueBuilder();
		boolean isExists = false;
		Account.Builder cryptoAccount = getAccount(ByteString.copyFrom(symbol));

		for (AccountCryptoValue.Builder acv : oAccountValue.getCryptosBuilderList()) {
			if (acv.getSymbol().equals(ByteString.copyFrom(symbol))) {
				isExists = true;
				boolean isNew = true;
				for (AccountCryptoToken.Builder act : acv.getTokensBuilderList()) {
					if (FastByteComparisons.equal(act.getHash().toByteArray(),
							oAccountCryptoToken.getHash().toByteArray())) {
						isNew = false;
						break;
					}
				}

				if (isNew) {
					acv.addTokens(oAccountCryptoToken);
					account.setValue(oAccountValue);
					touchAccount.put(encApi.hexEnc(address.toByteArray()), account.build());

					accountHelper.putStorage(cryptoAccount, oAccountCryptoToken.getHash().toByteArray(),
							oAccountCryptoToken.toByteArray());
					touchAccount.put(encApi.hexEnc(cryptoAccount.getAddress().toByteArray()), cryptoAccount.build());
				}
				break;
			}
		}

		if (!isExists) {
			AccountCryptoValue.Builder acv = AccountCryptoValue.newBuilder();
			acv.setSymbol(ByteString.copyFrom(symbol));
			acv.addTokens(oAccountCryptoToken);
			oAccountValue.addCryptos(acv);
			account.setValue(oAccountValue);
			touchAccount.put(encApi.hexEnc(address.toByteArray()), account.build());

			accountHelper.putStorage(cryptoAccount, oAccountCryptoToken.getHash().toByteArray(),
					oAccountCryptoToken.toByteArray());
			touchAccount.put(encApi.hexEnc(cryptoAccount.getAddress().toByteArray()), cryptoAccount.build());
		}
	}

	@Override
	public BigInteger addTokenBalance(ByteString address, byte[] token, BigInteger amount) {
		Account.Builder account = getAccount(address);
		AccountValue.Builder oAccountValue = account.getValueBuilder();
		for (AccountTokenValue.Builder atv : oAccountValue.getTokensBuilderList()) {
			if (atv.getToken().equals(ByteString.copyFrom(token))) {
				atv.setBalance(ByteString.copyFrom(ByteUtil
						.bigIntegerToBytes(ByteUtil.bytesToBigInteger(atv.getBalance().toByteArray()).add(amount))));

				account.setValue(oAccountValue);
				touchAccount.put(encApi.hexEnc(address.toByteArray()), account.build());

				return ByteUtil.bytesToBigInteger(atv.getBalance().toByteArray());
			}
		}
		AccountTokenValue.Builder oAccountTokenValue = AccountTokenValue.newBuilder();
		oAccountTokenValue.setToken(ByteString.copyFrom(token));
		oAccountTokenValue.setBalance(ByteString.copyFrom(ByteUtil.bigIntegerToBytes(amount)));
		oAccountValue.addTokens(oAccountTokenValue);

		account.setValue(oAccountValue);
		touchAccount.put(encApi.hexEnc(address.toByteArray()), account.build());

		return amount;
	}

	@Override
	public BigInteger addTokenFreezeBalance(ByteString address, byte[] token, BigInteger amount) {
		Account.Builder account = getAccount(address);
		AccountValue.Builder oAccountValue = account.getValueBuilder();
		for (AccountTokenValue.Builder atv : oAccountValue.getTokensBuilderList()) {
			if (atv.getToken().equals(ByteString.copyFrom(token))) {
				atv.setFreeze(ByteString.copyFrom(ByteUtil
						.bigIntegerToBytes(ByteUtil.bytesToBigInteger(atv.getFreeze().toByteArray()).add(amount))));

				account.setValue(oAccountValue);
				touchAccount.put(encApi.hexEnc(address.toByteArray()), account.build());

				return ByteUtil.bytesToBigInteger(atv.getFreeze().toByteArray());
			}
		}
		AccountTokenValue.Builder oAccountTokenValue = AccountTokenValue.newBuilder();
		oAccountTokenValue.setToken(ByteString.copyFrom(token));
		oAccountTokenValue.setFreeze(ByteString.copyFrom(ByteUtil.bigIntegerToBytes(amount)));
		oAccountValue.addTokens(oAccountTokenValue);

		account.setValue(oAccountValue);
		touchAccount.put(encApi.hexEnc(address.toByteArray()), account.build());

		return amount;
	}

	@Override
	public CryptoTokenOrigin getCryptoToken(byte[] symbol) {
		Account.Builder oAccount = getAccount(
				ByteString.copyFrom(encApi.hexDec(BlockChainConfig.cryptotoken_record_account_address)));
		CryptoTokenOrigin.Builder oCryptoTokenOrigin = accountHelper.getCryptoBySymbol(oAccount.build(), symbol);
		return oCryptoTokenOrigin.build();
	}

	@Override
	public AccountCryptoToken getCryptoTokenBalance(ByteString address, byte[] symbol, byte[] tokenHash) {
		Account.Builder account = getAccount(address);
		if (account == null) {
			return null;
		}
		for (AccountCryptoValue acv : account.getValue().getCryptosList()) {
			if (acv.getSymbol().equals(ByteString.copyFrom(symbol))) {
				for (AccountCryptoToken act : acv.getTokensList()) {
					if (FastByteComparisons.equal(act.getHash().toByteArray(), tokenHash)) {
						return act;
					}
				}

				break;
			}
		}
		return null;
	}

	@Override
	public byte[] getCryptoTokenBalanceByIndex(ByteString address, byte[] symbol, long index) {
		Account.Builder account = getAccount(address);
		if (account == null) {
			return null;
		}
		for (AccountCryptoValue acv : account.getValue().getCryptosList()) {
			if (acv.getSymbol().equals(ByteString.copyFrom(symbol))) {
				long i = 0;
				for (AccountCryptoToken act : acv.getTokensList()) {
					if (i == index) {
						return act.getHash().toByteArray();
					}
					i++;
				}
			}
		}
		return null;
	}

	@Override
	public int getCryptoTokenBalanceIndex(ByteString address, byte[] symbol, byte[] hash) {
		Account.Builder account = getAccount(address);
		if (account == null) {
			return -1;
		}
		for (AccountCryptoValue acv : account.getValue().getCryptosList()) {
			if (acv.getSymbol().equals(ByteString.copyFrom(symbol))) {
				int i = 0;
				for (AccountCryptoToken act : acv.getTokensList()) {
					if (FastByteComparisons.equal(act.getHash().toByteArray(), hash)) {
						return i;
					}
					i++;
				}
			}
		}
		return -1;
	}

	@Override
	public AccountCryptoToken getCryptoTokenByHash(byte[] symbol, ByteString hash) {
		Account.Builder oCryptoTokenAccount = getAccount(ByteString.copyFrom(encApi.sha3Encode(symbol)));
		AccountCryptoToken oAccountCryptoToken = accountHelper.getCryptoTokenByHash(oCryptoTokenAccount.build(),
				hash.toByteArray());
		if (oAccountCryptoToken != null) {
			return oAccountCryptoToken;
		}
		return null;
	}

	@Override
	public byte[] getCryptoTokenByIndex(byte[] symbol, int index) {
		Account.Builder account = getAccount(
				ByteString.copyFrom(encApi.hexDec(BlockChainConfig.cryptotoken_record_account_address)));
		if (account == null) {
			return null;
		}
		AccountCryptoToken oAccountCryptoToken = accountHelper.getCryptoTokenByIndex(account.build(),
				ByteString.copyFrom(symbol), index);
		if (oAccountCryptoToken == null) {
			return null;
		}
		return oAccountCryptoToken.getHash().toByteArray();
	}

	@Override
	public int getCryptoTokenSize(ByteString address, byte[] symbol) {
		Account.Builder account = getAccount(address);
		if (account == null) {
			return 0;
		}
		for (AccountCryptoValue acv : account.getValue().getCryptosList()) {
			if (acv.getSymbol().equals(ByteString.copyFrom(symbol))) {
				return acv.getTokensCount();
			}
		}
		return 0;
	}

	@Override
	public ERC20TokenValue getToken(byte[] token) {
		Account.Builder account = getAccount(
				ByteString.copyFrom(encApi.hexDec(BlockChainConfig.token_record_account_address)));
		if (account == null) {
			return null;
		} else {
			byte[] tokenBytes = accountHelper.getStorage(account, token);
			if (tokenBytes == null) {
				return null;
			} else {
				try {
					return ERC20TokenValue.parseFrom(tokenBytes);
				} catch (Exception e) {
				}
				return null;
			}
		}
	}

	@Override
	public BigInteger getTokenBalance(ByteString address, byte[] token) {
		try {
			Account.Builder account = getAccount(address);
			if (account != null) {
				for (AccountTokenValue atv : account.getValue().getTokensList()) {
					if (atv.getToken().equals(ByteString.copyFrom(token))) {
						return ByteUtil.bytesToBigInteger(atv.getBalance().toByteArray());
					}
				}
			}
		} catch (Throwable e) {
			byte[] bb = null;
			if (address != null) {
				bb = address.toByteArray();
			}
			if (bb == null) {
				bb = new byte[] { 0 };
			}
		}
		return BigInteger.ZERO;
	}

	@Override
	public BigInteger getTokenFreezeBalance(ByteString address, byte[] token) {
		Account.Builder account = getAccount(address);
		for (AccountTokenValue atv : account.getValue().getTokensList()) {
			if (atv.getToken().equals(ByteString.copyFrom(token))) {
				if (atv.getFreeze() == null || atv.getFreeze().equals(ByteString.EMPTY)) {
					return BigInteger.ZERO;
				}
				return ByteUtil.bytesToBigInteger(atv.getFreeze().toByteArray());
			}
		}
		return BigInteger.ZERO;
	}

	@Override
	public boolean isContract(ByteString address) {
		Account oAccount = GetAccount(address);
		if (oAccount == null) {
			return false;
		} else if (oAccount.getValue().getCode() != null && !oAccount.getValue().getCode().equals(ByteString.EMPTY)) {
			return true;
		}
		return false;
	}

	@Override
	public void removeCryptoTokenBalance(ByteString address, byte[] symbol, byte[] tokenHash) {
		Account.Builder account = getAccount(address);
		AccountValue.Builder oAccountValue = account.getValueBuilder();
		for (AccountCryptoValue.Builder acv : oAccountValue.getCryptosBuilderList()) {
			if (acv.getSymbol().equals(ByteString.copyFrom(symbol))) {
				for (int j = 0; j < acv.getTokensCount(); j++) {
					if (FastByteComparisons.equal(acv.getTokensBuilderList().get(j).getHash().toByteArray(),
							tokenHash)) {
						acv.removeTokens(j);

						account.setValue(oAccountValue);
						touchAccount.put(encApi.hexEnc(address.toByteArray()), account.build());
						break;
					}
				}

				break;
			}
		}
	}

	@Override
	public BigInteger subTokenBalance(ByteString address, byte[] token, BigInteger amount) {
		Account.Builder account = getAccount(address);
		AccountValue.Builder oAccountValue = account.getValueBuilder();
		for (AccountTokenValue.Builder atv : oAccountValue.getTokensBuilderList()) {
			if (atv.getToken().equals(ByteString.copyFrom(token))) {
				atv.setBalance(ByteString.copyFrom(ByteUtil.bigIntegerToBytes(
						ByteUtil.bytesToBigInteger(atv.getBalance().toByteArray()).subtract(amount))));

				account.setValue(oAccountValue);
				touchAccount.put(encApi.hexEnc(address.toByteArray()), account.build());
				return ByteUtil.bytesToBigInteger(atv.getBalance().toByteArray());
			}
		}

		return BigInteger.ONE.negate();
	}

	@Override
	public BigInteger subTokenFreezeBalance(ByteString address, byte[] token, BigInteger amount) {
		Account.Builder account = getAccount(address);
		AccountValue.Builder oAccountValue = account.getValueBuilder();
		for (AccountTokenValue.Builder atv : oAccountValue.getTokensBuilderList()) {
			if (atv.getToken().equals(ByteString.copyFrom(token))) {
				atv.setFreeze(ByteString.copyFrom(ByteUtil.bigIntegerToBytes(
						ByteUtil.bytesToBigInteger(atv.getFreeze().toByteArray()).subtract(amount))));

				account.setValue(oAccountValue);
				touchAccount.put(encApi.hexEnc(address.toByteArray()), account.build());
				return ByteUtil.bytesToBigInteger(atv.getFreeze().toByteArray());
			}
		}
		return BigInteger.ONE.negate();
	}

	@Override
	public byte[] mintCryptoToken(ByteString address, byte[] symbol, byte[] name, byte[] code, byte[] exdata,
			byte[] txHash, long timestamp) {
		try {
			CryptoTokenOrigin oCryptoTokenOrigin = getCryptoToken(symbol);
			if (oCryptoTokenOrigin == null || oCryptoTokenOrigin.getOriginValueCount() == 0) {
				return null;
			} else {
				CryptoTokenOriginValue oCryptoTokenOriginValue = oCryptoTokenOrigin
						.getOriginValue(oCryptoTokenOrigin.getOriginValueCount() - 1);

				if (!FastByteComparisons.equal(oCryptoTokenOriginValue.getOwner().toByteArray(),
						address.toByteArray())) {
					return null;
				} else if ((oCryptoTokenOriginValue.getEndIndex() + 1) > oCryptoTokenOriginValue.getTotal()) {
					return null;
				} else if (this.getBalance(address).compareTo(BlockChainConfig.token_lock_balance) < 0) {
					return null;
				}

				// 创建ERC721扣除手续费, 目前按照固定金额扣除, 后续需要按照token数量进行扣除, 待业务规则定义
				this.addBalance(address, BlockChainConfig.token_lock_balance.negate());

				CryptoTokenOriginValue.Builder oNewCryptoTokenOriginValue = CryptoTokenOriginValue.newBuilder();
				oNewCryptoTokenOriginValue.setOwner(address);
				if (oCryptoTokenOrigin.getOriginValueCount() > 0) {
					oNewCryptoTokenOriginValue.setStartIndex(oCryptoTokenOrigin
							.getOriginValue(oCryptoTokenOrigin.getOriginValueCount() - 1).getEndIndex());
				} else {
					oNewCryptoTokenOriginValue.setStartIndex(0);
				}
				oNewCryptoTokenOriginValue.setEndIndex(oNewCryptoTokenOriginValue.getStartIndex() + 1);
				oNewCryptoTokenOriginValue.setSymbol(ByteString.copyFrom(symbol));
				oNewCryptoTokenOriginValue.setTotal(oNewCryptoTokenOriginValue.getTotal());
				oNewCryptoTokenOriginValue.setExtData(ByteString.copyFrom(exdata));
				oNewCryptoTokenOriginValue.setTimestamp(timestamp);
				oNewCryptoTokenOriginValue.setTxHash(encApi.hexEnc(txHash));

				ByteString cryptoAccountAddress = ByteString.copyFrom(encApi.sha3Encode(symbol));
				Account cryptoAccount = GetAccount(cryptoAccountAddress);

				List<AccountCryptoToken> tokens = new ArrayList<>();
				AccountCryptoToken.Builder oAccountCryptoToken = AccountCryptoToken.newBuilder();
				oAccountCryptoToken.setCode(encApi.hexEnc(code));
				oAccountCryptoToken.setExtData(ByteString.copyFrom(exdata));
				oAccountCryptoToken.setIndex(oCryptoTokenOriginValue.getStartIndex());
				oAccountCryptoToken.setName(encApi.hexEnc(name));
				oAccountCryptoToken.setNonce(0);
				oAccountCryptoToken.setOwner(oCryptoTokenOriginValue.getOwner());
				oAccountCryptoToken.setOwnertime(timestamp);
				oAccountCryptoToken.setTotal(oCryptoTokenOriginValue.getTotal());
				oAccountCryptoToken.setTimestamp(timestamp);
				oAccountCryptoToken.setSymbol(ByteString.copyFrom(symbol));
				oAccountCryptoToken.clearHash();
				oAccountCryptoToken
						.setHash(ByteString.copyFrom(encApi.sha256Encode(oAccountCryptoToken.build().toByteArray())));

				oNewCryptoTokenOriginValue.addName(encApi.hexEnc(name));
				oNewCryptoTokenOriginValue.addCode(encApi.hexEnc(code));
				tokens.add(oAccountCryptoToken.build());

				this.saveStorage(cryptoAccountAddress, oAccountCryptoToken.getHash().toByteArray(),
						oAccountCryptoToken.build().toByteArray());
				this.saveStorage(
						ByteString.copyFrom(encApi.hexDec(BlockChainConfig.cryptotoken_record_account_address)), symbol,
						oCryptoTokenOrigin.toBuilder().addOriginValue(oNewCryptoTokenOriginValue).build()
								.toByteArray());

				this.addCryptoTokenBalance(address, symbol, oAccountCryptoToken.build());
				// 创建ERC721扣除手续费, 目前按照固定金额扣除, 后续需要按照token数量进行扣除, 待业务规则定义
				this.addBalance(ByteString.copyFrom(encApi.hexDec(BlockChainConfig.lock_account_address)),
						BlockChainConfig.token_lock_balance);
			}
		} catch (Exception e) {
			return null;
		}
		return null;
	}
}
