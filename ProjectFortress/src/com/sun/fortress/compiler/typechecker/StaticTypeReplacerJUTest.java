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

package com.sun.fortress.compiler.typechecker;

import junit.framework.TestCase;
import java.util.*;
import edu.rice.cs.plt.collect.CollectUtil;
import edu.rice.cs.plt.tuple.Option;
import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes_util.*;
import com.sun.fortress.parser_util.FortressUtil;
import com.sun.fortress.compiler.GlobalEnvironment;
import com.sun.fortress.compiler.index.*;

import static java.lang.Boolean.TRUE;
import static java.lang.Boolean.FALSE;
import static com.sun.fortress.compiler.typechecker.Types.*;
import static com.sun.fortress.nodes_util.NodeFactory.*;

public class StaticTypeReplacerJUTest extends TypeCheckerTestCase {
    
    private StaticTypeReplacer replacer;

    public void testLeafNodes() {
        List<StaticParam> params = makeSparams(makeStaticParam("K"),
                                               makeStaticParam("int n"),
                                               makeStaticParam("nat m"),
                                               makeStaticParam("bool b"),
                                               makeStaticParam("opr +"),
                                               makeStaticParam("dim d"),
                                               makeStaticParam("unit u"));
        List<StaticArg> args = makeSargs(makeTypeArg("ZZ32"),
                                         makeIntArg("-5"),
                                         makeIdArg("m"),
                                         makeBoolArg("true"),
                                         makeOprArg("-"),
                                         makeDimArg(makeDimRef("Length")),
                                         new UnitArg(makeVarRef(new Span(), makeQualifiedIdName("ft_"))));
                                         
        replacer = new StaticTypeReplacer(params, args);
        
        assertEqualTypes("ZZ32 -> ZZ32", "K -> K");
        //assertEqualTypes("List[\\ZZ32\\] -> Foo[\\ZZ32, -5\\]", "List[\\K\\] -> Foo[\\K, n\\]");
        assertEqualTypes("(ZZ32, String, ZZ32)", "(K, String, ZZ32)");
        assertEqualTypes("(true, -, -5, m, Length, ft_, ZZ32)", "(b, +, n, m, d, u, K)");
    }
    
    private void assertEqualTypes(String s, String t) {
        assertEquals(parseType(s).toString(), replacer.replaceIn(parseType(t)).toString());
    }
    
}
