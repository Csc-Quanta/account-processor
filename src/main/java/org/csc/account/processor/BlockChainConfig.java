package org.csc.account.processor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigInteger;

import com.google.protobuf.ByteString;
import org.csc.account.gens.Actimpl.PACTModule;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.tfw.mservice.NodeHelper;
import onight.tfw.outils.conf.PropHelper;
import org.csc.bcutil.Hex;

/**
 * 相关配置定义
 * 
 * @author lance
 * @since 2019.3.12 17:57
 */
@Slf4j
@Data
public class BlockChainConfig {
	public static PropHelper props = new PropHelper(null);
	public static String pwd = props.get("org.bc.manage.node.dev.pwd", KeyConstant.PWD);
	public static String keystoreNumber = props.get("org.bc.manage.node.keystore.num",
			String.valueOf(Math.abs(NodeHelper.getCurrNodeListenOutPort() - 5100 + 1)));
	public static int stableBlocks = props.get("org.brewchain.stable.blocks", KeyConstant.STABLE_BLOCK);

	public static String lock_account_address = props.get("org.csc.account.lock.address", null);
	public static String token_record_account_address = props.get("org.csc.account.token.address", null);
	public static String cryptotoken_record_account_address = props.get("org.csc.account.cryptotoken.address", null);
	public static String sidechain_record_account_address = props.get("org.csc.account.sidechain.address", null);

	public static ByteString lock_account_address_bytestring = ByteString
			.copyFrom(Hex.decode(BlockChainConfig.lock_account_address));
	public static ByteString token_record_account_address_bytestring = ByteString
			.copyFrom(Hex.decode(BlockChainConfig.token_record_account_address));
	public static ByteString cryptotoken_record_account_address_bytestring = ByteString
			.copyFrom(Hex.decode(BlockChainConfig.cryptotoken_record_account_address));
	public static ByteString sidechain_record_account_address_bytestring = ByteString
			.copyFrom(Hex.decode(BlockChainConfig.sidechain_record_account_address));
//	public static ByteString offline_address_bytestring = ByteString
//			.copyFrom(Hex.decode(BlockChainConfig.OFFLINE_ADDRESS));

	/** 子链申请创建账户, 用来同步子链信息, 所需费用 */
	public static BigInteger SIDE_CREATE_ACCOUNT_FEE = new BigInteger(
			props.get("org.csc.account.side.create.account.fee", "0"));
	/** 主链币 --> 离线钱包(手续费) */
	public static BigInteger OFFLINE_DOWN_FEE = new BigInteger(props.get("org.csc.account.offline.down.fee", "0"));
	/**
	 * 主链币 --> 离线钱包(扣除主链币后, 金额需要添加到该地址上面) 离线钱包 --> 主链币(金额需要扣除, 加入用户地址)
	 */
	public static String OFFLINE_ADDRESS = props.get("org.csc.account.offline.address", null);
	/** 离线钱包 --> 主链币(手续费) */
	public static BigInteger OFFLINE_UP_FEE = new BigInteger(props.get("org.csc.account.offline.up.fee", "0"));

	public static boolean isDev = props.get("org.brewchain.man.dev", "true").equals("true");
	public static boolean isDisableEC = props.get("org.csc.test.disable.ec", "false").equals("true");
	public static int defaultRollBackCount = props.get("org.brewchain.manage.rollback.count", 1);
	public static int accountVersion = props.get("org.csc.account.version", 0);
	public static int blockEpochMSecond = props.get("org.bc.dpos.blk.epoch.ms", 1000) / 1000;
	public static int blockEpochSecond = props.get("org.bc.dpos.blk.epoch.sec", 1);
	public static String nodeAccount = props.get("org.bc.manage.node.account", KeyConstant.DB_NODE_ACCOUNT_STR);
	public static String adminKey = props.get("org.bc.manage.admin.account", KeyConstant.DB_ADMINISTRATOR_KEY_STR);
	public static String nodeNet = props.get("org.bc.manage.node.net", KeyConstant.DB_NODE_NET_STR);
	public static String net = readNet();

	public static BigInteger token_lock_balance = new BigInteger(props.get("org.brewchain.token.lock.balance", "0"));
	public static BigInteger token_mint_balance = new BigInteger(props.get("org.brewchain.token.mint.balance", "0"));
	public static BigInteger token_burn_balance = new BigInteger(props.get("org.brewchain.token.burn.balance", "0"));
	public static BigInteger contract_lock_balance = new BigInteger(
			props.get("org.brewchain.contract.lock.balance", "0"));
	public static BigInteger minerReward = new BigInteger(props.get("block.miner.reward", "0"));
	public static BigInteger maxTokenTotal = readBigIntegerValue("org.brewchain.token.max.total", "0");
	public static BigInteger minTokenTotal = readBigIntegerValue("org.brewchain.token.min.total", "0");
	public static BigInteger minSanctionCost = readBigIntegerValue("org.brewchain.sanction.cost", "0");
	public static BigInteger minVoteCost = readBigIntegerValue("org.brewchain.vote.cost", "0");

	public static int cacheTxInitSize = props.get("org.csc.account.cache.tx.init", 10000);
	public static long cacheTxMaximumSize = props.get("org.csc.account.cache.tx.max", 1000000);

	/**
	 * ByteString -> String
	 * 
	 * @param hash Hash
	 * @return String
	 */
	public static String convert(ByteString hash) {
		if(hash == null || hash.isEmpty()){
			return "";
		}
		return Hex.toHexString(hash.toByteArray());
	}

	public static BigInteger readBigIntegerValue(String key, String defaultVal) {
		try {
			return new BigInteger(props.get(key, defaultVal));
		} catch (Exception e) {
			log.error("cannot read key::" + key, e);
		}
		return new BigInteger(defaultVal);
	}

	public static String readNet() {
		String network = "";
		BufferedReader br = null;
		FileReader fr = null;
		try {
			File networkFile = new File(".chainnet");
			if (!networkFile.exists() || !networkFile.canRead()) {
				// read default config
				network = nodeNet;
			}
			if (network == null || network.isEmpty()) {
				while (!networkFile.exists() || !networkFile.canRead()) {
					log.debug("waiting chain_net config...");
					Thread.sleep(1000);
				}

				fr = new FileReader(networkFile.getPath());
				br = new BufferedReader(fr);
				String line = br.readLine();
				if (line != null) {
					network = line.trim().replace("\r", "").replace("\t", "");
				}

			}
		} catch (Exception e) {
			log.error("fail to read net config");
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
				}
			}
			if (fr != null) {
				try {
					fr.close();
				} catch (IOException e) {
				}
			}
		}
		return network;
	}
}
