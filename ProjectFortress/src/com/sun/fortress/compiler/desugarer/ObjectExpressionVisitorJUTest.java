/*******************************************************************************
    Copyright 2008 Sun Microsystems, Inc.,
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

package com.sun.fortress.compiler.desugarer;

import junit.framework.TestCase;

import java.io.FileNotFoundException;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

import com.sun.fortress.repository.ProjectProperties;
import com.sun.fortress.repository.CacheBasedRepository;
import com.sun.fortress.Shell;
import com.sun.fortress.interpreter.env.ComponentWrapper;
import com.sun.fortress.interpreter.evaluator.values.FValue;
import com.sun.fortress.syntax_abstractions.parser.PreParser;
import com.sun.fortress.nodes_util.ASTIO;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.useful.WireTappedPrintStream;

public class ObjectExpressionVisitorJUTest extends TestCase {

    private static final String testsDir =
        ProjectProperties.FORTRESS_AUTOHOME + "/ProjectFortress/tests";
    private static final char SEP = File.separatorChar;

    public void testObjectCC()
        throws FileNotFoundException, IOException, Throwable {
        runFile("objectCC_immutable.fss");
    }

    public void testObjectCC_Mutables()
        throws FileNotFoundException, IOException, Throwable {
        runFile("objectCC_mutVar1.fss");
        runFile("objectCC_mutVar2.fss");
        runFile("objectCC_mutable.fss");
    }

    public void testObjectCC_Mutli_ObjExpr_Mutables()
        throws FileNotFoundException, IOException, Throwable {
        runFile("objectCC_multi_objExpr_mutVar1.fss");
        runFile("objectCC_multi_objExpr_mutVar2.fss");
    }

    public void testObjectCC_StaticParams()
        throws FileNotFoundException, IOException, Throwable {
        runFile("objectCC_staticParams.fss");
    }

    public void testObjectCC_Shadowing()
        throws FileNotFoundException, IOException, Throwable {
        runFile("objectCC_shadowTest.fss");
    }

    public void testObjectCC_Label()
        throws FileNotFoundException, IOException, Throwable {
        runFile("objectCC_label.fss");
    }

    private void runFile(String fileName)
        throws FileNotFoundException, IOException, Throwable {

        String file = testsDir + SEP + fileName;

        // do not print stuff to stdout for JUTests
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;

        try {
            ComponentWrapper.noCache = true;
            WireTappedPrintStream wt_err = WireTappedPrintStream.make(
                    System.err, true);
            WireTappedPrintStream wt_out = WireTappedPrintStream.make(
                    System.out, true);
            System.setErr(wt_err);
            System.setOut(wt_out);
            System.out.println("Evaluating " + file + "...");
            FValue original = Shell.eval(file);
            // Delete the cached file from evaluating the original file!!!
            ASTIO.deleteJavaAst(CacheBasedRepository.cachedCompFileName(
                    ProjectProperties.ANALYZED_CACHE_DIR, PreParser.apiName(
                            NodeFactory.makeAPIName(file), new File(file)
                                    .getCanonicalFile())));
            String name = file.substring(0, file.lastIndexOf("."));
            String tfs = name + ".tfs";

            String[] command = new String[] { "desugar", "-out", tfs, file };
            System.out.println("Command: fortress desugar -out " + tfs + " "
                               + file);
            Shell.main(command);
            String generated = System.getProperty("java.io.tmpdir") + SEP
                + fileName;

            command = new String[] { "unparse", "-unqualified", "-unmangle",
                    "-out", generated, tfs };
            System.out.println("Command: fortress unparse -unqualified -unmangle -out "
                               + generated + " " + tfs);
            Shell.main(command);
            ASTIO.deleteJavaAst(tfs);

            // must turn these passes off
            Shell.setTypeChecking(false);
            Shell.setObjExprDesugaring(false);

            System.out.println("Evaluating " + generated + "...");
            assertEquals(original, Shell.eval(generated));
        } finally {
            ComponentWrapper.noCache = false;
            System.setErr(oldErr);
            System.setOut(oldOut);
        }
    }

}
