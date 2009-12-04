/*******************************************************************************
 Copyright 2009 Sun Microsystems, Inc.,
 4150 Network Circle, Santa Clara, California 95054, U.S.A.
 All rights reserved.

 U.S. Government Rights - Commercial software.
 Government users are subject to the Sun Microsystems, Inc. standard
 license agreement and applicable provisions of the FAR and its supplements.

 Use is subject to license terms.

 This distribution may include materials developed by third parties.

 Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered
 trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
 ******************************************************************************/

package com.sun.fortress.tests.unit_tests;

import com.sun.fortress.Shell; 
import com.sun.fortress.compiler.phases.PhaseOrder; 
import com.sun.fortress.repository.ProjectProperties;
import junit.framework.Test;
import junit.framework.TestSuite;

import java.io.IOException;

/**
 * Junit wrapper that runs all the programs found in demos, looking for
 * exceptions and/or "FAIL".  The name was chosen to NOT trigger the
 * filters for pattern-matching unit tests within ant; this is run
 * explicitly, as part of nightly tests.
 *
 * @author chase
 */
public class DemoTests {


    public static void main(String[] args) throws IOException {

        junit.textui.TestRunner.run(suite());
    }

    public static Test suite() throws IOException {
        Shell.setPhaseOrder(PhaseOrder.interpreterPhaseOrder);
        boolean failsOnly = !("1".equals(System.getenv("FORTRESS_JUNIT_VERBOSE")));

        String testDir = ProjectProperties.BASEDIR + "demos";
        String s = System.getProperty("demos");
        if (s != null) {
            testDir = s;
        }
        TestSuite suite = new TestSuite("Test all .fss files in 'demos'.");
        //$JUnit-BEGIN$
        suite.addTest(FileTests.interpreterSuite(testDir, failsOnly, false));
        //$JUnit-END$
        return suite;
    }

}
