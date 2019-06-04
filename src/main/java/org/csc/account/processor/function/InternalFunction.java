package org.csc.account.processor.function;

import org.csc.account.api.IAccountHelper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InternalFunction {

	/**
	 * 挖矿奖励
	 * 
	 * @param accountHelper
	 * @param blackHoleAddress
	 * @param coinBase
	 * @param balance
	 * @throws Exception
	 */
	public static void MinerReward(IAccountHelper accountHelper, String... params) throws Exception {
		String blackHoleAddress = "";
		String balance = "0";
		String coinBase = "";
		log.debug(String.format("call MinerReward!"));
		// 黑洞地址
	}

	/**
	 * 挖矿惩罚
	 * 
	 * @param accountHelper
	 * @param blackHoleAddress
	 * @param coinBase
	 * @param balance
	 * @throws Exception
	 */
	public static void MinerPunish(IAccountHelper accountHelper, byte[] blackHoleAddress, byte[] coinBase, long balance)
			throws Exception {
		// 黑洞地址
	}
}
