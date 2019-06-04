package org.csc.account.core.actuator;

import java.lang.reflect.Constructor;
import java.util.Map;

import org.csc.account.api.IAccountHelper;
import org.csc.account.api.IStateTrie;
import org.csc.account.api.ITransactionActuator;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.api.enums.TransTypeEnum;
import org.csc.account.core.actuator.contract.ActuatorCallContract;
import org.csc.account.core.actuator.contract.ActuatorCreateContract;
import org.csc.account.core.actuator.offline.DownChainTransaction;
import org.csc.account.core.actuator.offline.UpChainTransaction;
import org.csc.account.core.actuator.side.ActuatorSideChainSync;
import org.csc.account.core.actuator.side.CreateSideChainTransaction;
import org.csc.account.core.actuator.token.ActuatorBurnToken;
import org.csc.account.core.actuator.token.ActuatorCreateToken;
import org.csc.account.core.actuator.token.ActuatorLockTokenTransaction;
import org.csc.account.core.actuator.token.ActuatorMintToken;
import org.csc.account.core.actuator.token.ActuatorTokenTransaction;
import org.csc.bcapi.EncAPI;
import org.csc.evmapi.gens.Block.BlockEntity;

import com.google.common.collect.Maps;

import lombok.extern.slf4j.Slf4j;

/**
 * 创建交易执行Actor
 * 
 * @author lance
 * @since 2018.12.26
 */
@Slf4j
public class ActuatorFactory {
	private final static Map<TransTypeEnum, Constructor<? extends ITransactionActuator>> ACTOR = Maps
			.newConcurrentMap();
	static {
		try {
			@SuppressWarnings("rawtypes")
			Class[] clazz = { IAccountHelper.class, ITransactionHelper.class, BlockEntity.class, EncAPI.class,
					IStateTrie.class };
			ACTOR.put(TransTypeEnum.TYPE_CreateUnionAccount, ActuatorCreateUnionAccount.class.getConstructor(clazz));
			ACTOR.put(TransTypeEnum.TYPE_TokenTransaction, ActuatorTokenTransaction.class.getConstructor(clazz));
			ACTOR.put(TransTypeEnum.TYPE_UnionAccountTransaction,
					ActuatorUnionAccountTransaction.class.getConstructor(clazz));
			// ACTOR.put(TransTypeEnum.TYPE_CallInternalFunction,
			// ActuatorCallInternalFunction.class.getConstructor(clazz));
			ACTOR.put(TransTypeEnum.TYPE_CryptoTokenTransaction,
					ActuatorCryptoTokenTransaction.class.getConstructor(clazz));
			ACTOR.put(TransTypeEnum.TYPE_LockTokenTransaction,
					ActuatorLockTokenTransaction.class.getConstructor(clazz));
			ACTOR.put(TransTypeEnum.TYPE_CreateToken, ActuatorCreateToken.class.getConstructor(clazz));
			ACTOR.put(TransTypeEnum.TYPE_CreateCryptoToken, ActuatorCreateCryptoToken.class.getConstructor(clazz));
			ACTOR.put(TransTypeEnum.TYPE_Sanction, ActuatorSanctionTransaction.class.getConstructor(clazz));
			ACTOR.put(TransTypeEnum.TYPE_UnionAccountTokenTransaction,
					ActuatorUnionAccountTokenTransaction.class.getConstructor(clazz));
			//创建合约, 调用合约
			ACTOR.put(TransTypeEnum.TYPE_CreateContract, ActuatorCreateContract.class.getConstructor(clazz));
			ACTOR.put(TransTypeEnum.TYPE_CallContract, ActuatorCallContract.class.getConstructor(clazz));
			//增发燃烧token
			ACTOR.put(TransTypeEnum.TYPE_MintToken, ActuatorMintToken.class.getConstructor(clazz));
			ACTOR.put(TransTypeEnum.TYPE_BurnToken, ActuatorBurnToken.class.getConstructor(clazz));
			//主链子链注册同步交易
			ACTOR.put(TransTypeEnum.TYPE_sideChainSync, ActuatorSideChainSync.class.getConstructor(clazz));
			ACTOR.put(TransTypeEnum.TYPE_sideChainReg, CreateSideChainTransaction.class.getConstructor(clazz));
			//交易上链和交易下链交易
			ACTOR.put(TransTypeEnum.TYPE_UP_TRANS, UpChainTransaction.class.getConstructor(clazz));
			ACTOR.put(TransTypeEnum.TYPE_DOWN_TRANS, DownChainTransaction.class.getConstructor(clazz));
		} catch (NoSuchMethodException | SecurityException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 动态创建Actor
	 * 
	 * @param type
	 *            Tx执行类型
	 * @param accountHelp
	 *            IAccountHelper
	 * @param txHelper
	 *            TransactionHelper
	 * @param block
	 *            BlockEntity
	 * @param api
	 *            EncAPI
	 * @param trie
	 *            IStateTrie
	 * @return 返回对象ITransactionActuator
	 */
	public static ITransactionActuator get(TransTypeEnum type, IAccountHelper accountHelp, ITransactionHelper txHelper,
			BlockEntity block, EncAPI api, IStateTrie trie) {
		try {
			if (log.isDebugEnabled()) {
				log.debug("===>TransTypeEnum: {}", type.name());
			}

			if (ACTOR.containsKey(type)) {
				return ACTOR.get(type).newInstance(accountHelp, txHelper, block, api, trie);
			}

			return new ActuatorDefault(accountHelp, txHelper, block, api, trie);
		} catch (Exception e) {
			log.error("Get Tx Actuator Fail. ", e);
		}

		return null;
	}
}
