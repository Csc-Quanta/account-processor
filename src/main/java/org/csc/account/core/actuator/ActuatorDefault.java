package org.csc.account.core.actuator;

import com.google.protobuf.ByteString;
import org.csc.account.api.IAccountHelper;
import org.csc.account.api.IStateTrie;
import org.csc.account.api.ITransactionActuator;
import org.csc.account.api.ITransactionHelper;
import org.csc.account.exception.BlockException;
import org.csc.account.exception.TransactionParameterInvalidException;
import org.csc.bcapi.EncAPI;
import org.csc.evmapi.gens.Act.Account;
import org.csc.evmapi.gens.Block.BlockEntity;
import org.csc.evmapi.gens.Tx;

import java.util.Map;

/**
 * Default Actor
 * @author lance
 * @since 2019.1.10 11:42
 */
public class ActuatorDefault extends AbstractTransactionActuator implements ITransactionActuator {

	public ActuatorDefault(IAccountHelper oAccountHelper, ITransactionHelper oTransactionHelper, BlockEntity oBlock,
			EncAPI encApi, IStateTrie oStateTrie) {
		super(oAccountHelper, oTransactionHelper, oBlock, encApi, oStateTrie);
	}

	@Override
	public void onPrepareExecute(Tx.Transaction tx, Map<ByteString, Account.Builder> accounts)
			throws BlockException {
		if (tx.getBody().getInput() == null || tx.getBody().getOutputsCount() == 0) {
			throw new TransactionParameterInvalidException("parameter invalid, inputs or outputs must not be null");
		}

		super.onPrepareExecute(tx, accounts);
	}
}
