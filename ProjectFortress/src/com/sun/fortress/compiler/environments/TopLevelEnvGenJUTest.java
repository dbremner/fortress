package com.sun.fortress.compiler.environments;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import junit.framework.Test;
import junit.framework.TestSuite;

import com.sun.fortress.compiler.Fortress;
import com.sun.fortress.exceptions.StaticError;
import com.sun.fortress.interpreter.drivers.ProjectProperties;
import com.sun.fortress.interpreter.evaluator.BaseEnv;
import com.sun.fortress.interpreter.evaluator.values.FInt;
import com.sun.fortress.shell.CacheBasedRepository;
import com.sun.fortress.useful.Path;
import com.sun.fortress.useful.TestCaseWrapper;

public class TopLevelEnvGenJUTest extends TestCaseWrapper {
	
	   public TopLevelEnvGenJUTest() {
	        super("TopLevelEnvGenJUTest");
	    }

	    public static Test suite() {
	        return new TestSuite(TopLevelEnvGenJUTest.class);
	    }

	    public static void main(String args[]) {
	        junit.textui.TestRunner.run(suite());
	    }	   
	   
	    
	    public void testCompiledEnvironment() throws IOException, 
	    							InstantiationException, IllegalAccessException {

            compileTestProgram();            
	        
			BaseEnv environment = loadEnvironment();

			FInt three = FInt.make(3);
			FInt seven = FInt.make(7);
			FInt thirteen = FInt.make(13);
			
			environment.putValueUnconditionally("run", three);

			// Now test hash collisions
			environment.putValueUnconditionally("Aa", seven);
			environment.putValueUnconditionally("BB", thirteen);
			
			assertEquals(environment.getValueRaw("run"), three);
			assertEquals(environment.getValueRaw("Aa"), seven);			
			assertEquals(environment.getValueRaw("BB"), thirteen);
			assertNull(environment.getValueRaw("Chupacabra"));			
			
	    }

		private BaseEnv loadEnvironment() throws FileNotFoundException,
				IOException, InstantiationException, IllegalAccessException {
			SimpleClassLoader classLoader = new SimpleClassLoader();
			File classfile = new File(ProjectProperties.BYTECODE_CACHE_DIR + 
					File.separator + "TestCompiledEnvironmentsEnv.class");
			byte[] bytecode = new byte[(int) classfile.length()];
			FileInputStream classStream = new FileInputStream(classfile);
			int read = classStream.read(bytecode);
			if (read != classfile.length()) {
				fail("Expected to read " + classfile.length() + " bytes but only read " + read + " bytes.");
			}
			
			Class generatedClass = classLoader.defineClass("TestCompiledEnvironmentsEnv", bytecode);
			Object envObject = generatedClass.newInstance();
			BaseEnv environment = (BaseEnv) envObject;
			return environment;
		}

		private void compileTestProgram() {
			Fortress fortress = new Fortress(new CacheBasedRepository(ProjectProperties.ANALYZED_CACHE_DIR));

            Path path = ProjectProperties.SOURCE_PATH;
            String s = ProjectProperties.BASEDIR + "tests" + 
            	File.separator + "TestCompiledEnvironments.fss";
            
            File file = new File(s);
            s = file.getPath();

            if (s.contains(File.separator)) {
                String head = s.substring(0, s.lastIndexOf(File.separator));
                s = s.substring(s.lastIndexOf(File.separator)+1, s.length());
                path = path.prepend(head);
            }                      
            
            Iterable<? extends StaticError> errors = fortress.compile(path, s);
            
            for (StaticError error: errors) {
                fail(error.toString());
            }
		}	    
}
