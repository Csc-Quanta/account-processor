package org.csc.account.processor;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import org.csc.account.util.NodeDef;
import org.csc.evmapi.gens.Act.Account;

public class KeyConstant {
	public static final int STABLE_BLOCK = 50;
	public static final int BLOCK_REWARD = 6;
	public static final int BLOCK_REWARD_DELAY = 5;
	public static final String BC_UNSEND_TX = "bc_unsend_tx";
	public static final byte[] BC_UNSEND_TX_BYTE = BC_UNSEND_TX.getBytes();
	public static final String BC_CONFIRM_TX = "bc_confirm_tx";
	public static final byte[] BC_CONFIRM_TX_BYTE = BC_CONFIRM_TX.getBytes();
	public static final BigInteger EMPTY_NONCE = BigInteger.ZERO;
	public static final BigInteger EMPTY_BALANCE = BigInteger.ZERO;
	public static final int GENESIS_NUMBER = 0;
	public static final byte[] GENESIS_HASH = String.valueOf(GENESIS_NUMBER).getBytes();
	public static final int DEFAULT_BLOCK_TX_COUNT = 20000;
	public static final byte[] DB_CURRENT_BLOCK = "DB_CURRENT_BLOCK_y0yXF4880c".getBytes();
	public static final byte[] DB_CURRENT_MAX_BLOCK = "DB_CURRENT_MAX_BLOCK_y0yXF4880c".getBytes();

	public static final String DB_EXISTS_CRYPTO_TOKEN_STR = "DB_EXISTS_CRYPTO_TOKEN_7513d2287ce94891ba227ba83aa6fe51";
	public static final byte[] DB_EXISTS_TOKEN = "DB_EXISTS_TOKEN_7513d2287ce94891ba227ba83aa6fe51".getBytes();
	public static final byte[] DB_EXISTS_CONTRACT = "DB_EXISTS_CONTRACT_7513d2287ce94891ba227ba83aa6fe51".getBytes();
	public static final String DB_NODE_ACCOUNT_STR = "MANAGE_NODE_ACCOUNT_7513d2287ce94891ba227ba83aa6fe51";
	public static final String DB_ADMINISTRATOR_KEY_STR = "ADMINISTRATOR_KEY_7513d2287ce94891ba227ba83aa6fe51";
	public static final String DB_NODE_NET_STR = "testnet";

	
	public static final int CACHE_SIZE = 200;

	public static boolean isStart = false;
	public static String PWD;
	
	public static LinkedBlockingQueue<Map<String, Account.Builder>> QUEUE = new LinkedBlockingQueue<>();

	
}
