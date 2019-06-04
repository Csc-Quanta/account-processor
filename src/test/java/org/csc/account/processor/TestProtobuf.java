package org.csc.account.processor;
//import org.csc.account.gens.

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.junit.Ignore;
import org.junit.Test;

import java.util.Objects;

@Slf4j
public class TestProtobuf {

	@Test
    public void testParseJson() {
        String item1 = "abc";
        String item2 = "abc";

        log.info("===>{}", Objects.equals(ByteString.copyFrom(item1.getBytes()), ByteString.copyFrom(item2.getBytes())));
	}
}
