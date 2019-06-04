
package org.csc.account.core.processor;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import onight.osgi.annotation.NActorProvider;
import onight.tfw.ntrans.api.ActorService;
import onight.tfw.ntrans.api.annotation.ActorRequire;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.csc.account.api.IProcessor;
import org.csc.account.api.IProcessorManager;

@Data
@Slf4j
@NActorProvider
@Instantiate(name = "Processor_Manager")
@Provides(specifications = {ActorService.class})
public class ProcessorManager implements ActorService, IProcessorManager {
    @ActorRequire(name = "V4_Processor", scope = "global")
    private V4Processor v4Processor;

    @Override
    public IProcessor getProcessor(int version) {
        return v4Processor;
    }
}
