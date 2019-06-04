package org.csc.account.processor;

import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Validate;
import org.csc.account.gens.Actimpl.PACTModule;

import com.google.protobuf.Message;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.oapi.scala.commons.SessionModules;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.ntrans.api.ActorService;

@NActorProvider
@Provides(specifications = { ActorService.class })
@Slf4j
@Data
public class ApplicationStart extends SessionModules<Message> {

	@Override
	public String[] getCmds() {
		return new String[] { "PROCESSOR" };
	}

	@Override
	public String getModule() {
		return PACTModule.ACT.name();
	}

	@Validate
	public void startup() {
		try {
			new Thread(new ProcessorStartThread()).start();
		} catch (Exception e) {
			// e.printStackTrace();
			log.error("dao注入异常", e);
		}
	}

	class ProcessorStartThread extends Thread {
		@Override
		public void run() {
			
		}

	}
}
