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

package com.sun.fortress.interpreter.evaluator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import com.sun.fortress.useful.Useful;
import com.sun.fortress.exceptions.ProgramError;
import com.sun.fortress.interpreter.env.BetterEnv;
import com.sun.fortress.interpreter.evaluator.tasks.BaseTask;
import com.sun.fortress.interpreter.evaluator.tasks.FortressTaskRunner;
import com.sun.fortress.interpreter.evaluator.tasks.FortressTaskRunnerGroup;
import com.sun.fortress.interpreter.evaluator.types.FType;
import com.sun.fortress.interpreter.evaluator.types.FTypeVoid;
import com.sun.fortress.interpreter.evaluator.types.FTypeTuple;
import com.sun.fortress.interpreter.evaluator.values.FInt;
import com.sun.fortress.useful.HasAt;

public class EvaluatorJUTest extends com.sun.fortress.useful.TestCaseWrapper  {

    public EvaluatorJUTest() {
        super("EvaluatorJUTest");
    }

    BufferedReader bs(String s) {
        return new BufferedReader(new StringReader(s));
    }

    class TestTask extends BaseTask {
 public void print() {
     System.out.println("TestTask");
 }

 public void compute() {
     FortressTaskRunner runner = (FortressTaskRunner) Thread.currentThread();
     runner.setCurrentTask(this);

     try {
  BetterEnv e = (BetterEnv) BetterEnv.primitive();
  e.bless();
  BetterEnv s = new BetterEnv(e, "s");
  s.bless();
  BetterEnv s1 = new BetterEnv(s, "s1");
  s1.putVariable("x", FInt.make(0));
  s1.bless();
  BetterEnv s2 = new BetterEnv(s1, "s2");
  s2.putValue("y", FInt.make(10));
  s2.bless();
  BetterEnv s3 = new BetterEnv(s2, "s3");
  s3.bless();
  // Fails to run because FInts lack a type, hence cannot be assigned.
//  HasAt at = new HasAt.FromString("EvaluatorJUTest assignValue");
//  s3.assignValue(at,"x", FInt.make(1));
//  assertTrue(s2.getValue("x").getInt() == 1);
 s3.dump(System.out);
     }
     catch (IOException e) {
  throw new RuntimeException(e);
     }
 }
    }

  public void testEnvironment2() throws IOException {
      FortressTaskRunnerGroup group = new FortressTaskRunnerGroup(1);
      TestTask testTask = new TestTask();
      group.invoke(testTask);
  }

  public void testEnvironment3() throws IOException {
      BetterEnv e =  (BetterEnv) BetterEnv.primitive("primitive");
      e.bless();
      BetterEnv s = new BetterEnv(e, "s");
      s.bless();
      BetterEnv s1 = new BetterEnv(s, "s1");
      FType t2 = FTypeVoid.ONLY;
      FType t1 = FTypeTuple.make(Useful.list(t2,t2,t2));
      FType t3 = FTypeTuple.make(Useful.list(t2,t2));
      s1.putType("x", t1);
      s1.bless();
      BetterEnv s2 = new BetterEnv(s1, "s2");
      s2.putType("y", t2);
      s2.bless();
      BetterEnv s3 = new BetterEnv(s2, "s3");
      s3.putType("x", t3);
      s3.bless();
      assertTrue(s3.getType("x") == t3);
      assertTrue(s2.getType("x") == t1);
      assertTrue(s3.getType("y") == t2);
      assertTrue(s2.getType("y") == t2);
      assertTrue(s1.getType("x") == t1);
      try {
          s1.getType("y");
          fail("Expected exception was not thrown");
      } catch (ProgramError ex) {
          System.out.println("Saw expected exception " + ex);
      }
      try {
          s3.getValue("x");
          fail("Expected exception was not thrown");
      } catch (ProgramError ex) {
          System.out.println("Saw expected exception " + ex);
      }
      s3.dump(System.out);
  }

  public void testEnvironment4() throws IOException {
      Environment e = BetterEnv.primitive("primitive");
      e.putBool("x", Boolean.TRUE);
      e.putNat("x", Integer.valueOf(0));
      // FType t1 = FTypeInt.ONLY;
      // e.putType("x", t1);
      // Cannot do this -- the bool/nat params overlap in value.  e.putValue("x", FInt.make(1));
      assertEquals(Boolean.TRUE, e.getBool("x"));
      assertEquals(Integer.valueOf(0), e.getNat("x"));
      // assertEquals(t1, e.getType("x"));
      // assertEquals(1, e.getValue("x").getInt());
  }

  public void testBuildEnvironment1() throws IOException {
      // totally obsolete w/ changes to AST.

//      String s = "(Component @\"../samples/Scopes.fss\",14:0" +
//      " name=(DottedId @1:16 names=[\"Scopes\"])" +
//      " defs=[" +
//      "  (VarDecl @4:8" +
//      "   init=(IntLiteralExpr text=\"1\" val=1)" +
//      "   name=(Id @4:4 name=\"aVar\"))" +
//      "  (ObjectDecl @9:3" +
//      "   contract=(Contract @6:14)" +
//      "   name=(Id @6:8 name=\"O\")" +
//      "   params=(Some val=[" +
//      "     (Param @6:13" +
//      "      name=(Id @6:11 name=\"p1\")" +
//      "      type=(Some val=(VarType @6:13 name=(DottedId names=[\"ZZ32\"]))))])" +
//      "   defs=[" +
//      "    (VarDecl @7:13" +
//      "     init=(IntLiteralExpr text=\"2\" val=2)" +
//      "     name=(Id @7:4 name=\"f1\")" +
//      "     type=(Some val=(VarType @7:9 name=(DottedId names=[\"ZZ32\"]))))" +
//      "    (FnDecl @8:11" +
//      "     body=(VarRef var=(Id name=\"f1\"))" +
//      "     contract=(Contract @8:6)" +
//      "     name=(Fun" +
//      "      name_=(Id @8:4 name=\"g1\")))])" +
//      "  (FnDecl @11:17" +
//      "   body=(OpExpr @11:13~17" +
//      "    op=(Opr @11:15 op=(Op name=\"+\"))" +
//      "    args=[" +
//      "     (VarRef @11:13 var=(Id name=\"aVar\"))" +
//      "     (VarRef @11:17 var=(Id name=\"x\"))])" +
//      "   contract=(Contract @11:6)" +
//      "   name=(Fun" +
//      "    name_=(Id @11:3 name=\"fn1\"))" +
//      "   params=[" +
//      "    (Param @11:5" +
//      "     name=(Id name=\"x\"))])]" +
//      " exports=[" +
//      "  (Export @2:17" +
//      "   name=(DottedId names=[\"executable\"]))])";
//
//      Lex l = new Lex(bs(s));
//
//      Unprinter up = new Unprinter(l);
//      l.name(); // Reading "("
//      Node x = up.readNode(l.name()); // Read name of class, pass to node com.sun.fortress.interpreter.reader.
//      BetterEnv e = BetterEnv.primitive();
//      assert(null!=e.getType("ZZ32"));
//      assert(null!=e.getType("String"));
//      BuildEnvironments be = new BuildEnvironments(e);
//      assert(null!=e.getType("ZZ32"));
//      assert(null!=e.getType("String"));
//      x.visit(be);
//
//      // SComponent comp = e.getComponent("Scopes"); // TODO, need to implement this
//      //FValue aVar = e.getValue("aVar");
//      FValue fn1 = e.getValue("fn1");
//      FValue Ov = e.getValue("O");
//      //FType Ot = e.getType("O");
//
//      assertTrue(fn1 instanceof Closure);
//      assertTrue(Ov instanceof Constructor);

      //Constructor con = (Constructor) Ov;

      //Environment interior = con.getWithin();

  }

// public void testForAssignment() throws IOException {
//    // x = 3
//    String s =        "(Assignment \n"+
//      "       lhs=(VarRef  var=(Id name=\"x\"))\n"+
//      "       rhs=(IntLiteralExpr text=\"3\" val=3))\n";
//    Lex l = new Lex(bs(s));
//
//    Unprinter up = new Unprinter(l);
//    l.name(); // Reading "("
//    Node x = up.readNode(l.name()); // Reading name of class
//
//    assertTrue (x instanceof com.sun.fortress.interpreter.nodes.Assignment);
//    Evaluator eval = new Evaluator(x);
//    eval.e.putVariable("x", FInt.make(7));
//    x.visit(eval);
//    assertTrue(eval.e.getValue("x").getInt() == 3);
//  }

    /* This test assumes that primops are inserted into the initial
     * environment by fiat.  This is no longer true; there are no
     * primops unless we load the library. */

  // public void testForBlock() throws IOException {
  //   // x * y
  //   // x + y
  //   String s = "(Block \n" +
  //     " exprs=[ \n" +
  //     " (OpExpr \n " +
  //     " op=(Opr op=(Op name=\"*\")) \n " +
  //     " args=[ \n " +
  //     " (VarRef var=(Id name=\"x\")) \n " +
  //     " (VarRef var=(Id name=\"y\"))]) \n " +
  //     " (OpExpr \n " +
  //     " op=(Opr op=(Op name=\"+\")) \n " +
  //     " args=[ \n " +
  //     " (VarRef var=(Id name=\"x\")) \n " +
  //     " (VarRef var=(Id name=\"y\"))])]) \n ";
  //   Lex l = new Lex(bs(s));
  //   Unprinter up = new Unprinter(l);
  //   l.name(); // Reading "("
  //   Node x = up.readNode(l.name()); // Reading name of class
  //   assertTrue (x instanceof com.sun.fortress.interpreter.nodes.Block);
  //   Evaluator eval = new Evaluator(x);
  //   eval.e.putValue("x", FInt.make(7));
  //   eval.e.putValue("y", FInt.make(5));
  //   FInt res = (FInt) x.visit(eval);
  //   assertTrue(res.getInt() == 12);
  // }

  /* As above this test assumes the availability of primops. */

  // public void testForChainExpr() throws IOException {
  //   String s = "(ChainExpr \n" +
  //     " first=(VarRef var=(Id name=\"i\")) \n" +
  //     " links=[ \n " +
  //     " (Pair \n " +
  //     " (Op name=\"<\") \n " +
  //     " (VarRef var=(Id name=\"j\"))) \n " +
  //     " (Pair \n " +
  //     " (Op name=\"<\") \n " +
  //     " (IntLiteralExpr text=\"10\" val=10))] \n " +
  //     " props=[ \n " +
  //     " \"parenthesized\"]))] ";
  //   Lex l = new Lex(bs(s));
  //   Unprinter up = new Unprinter(l);
  //   l.name(); // Reading "("
  //   Node x = up.readNode(l.name()); // Reading name of class
  //   assertTrue (x instanceof com.sun.fortress.interpreter.nodes.ChainExpr);
  //   Evaluator eval = new Evaluator(x);
  //   eval.e.putValue("i",FInt.make(1));
  //   eval.e.putVariable("j",FInt.make(7));
  //   FBool res = (FBool) x.visit(eval);
  //   assertTrue(res.getBool());
  //   try {
  //       eval.e.putValue("j",FInt.make(12)); // Second put should fail
  //       fail("Should have thrown RedefinitionError");
  //   } catch (RedefinitionError ex) {
  //       // Expected
  //   }
  //   res  = (FBool) x.visit(eval);
  //   assertTrue(res.getBool());
  //   eval.e.assignValue(x,"j",FInt.make(12));
  //   res  = (FBool) x.visit(eval);
  //   assertTrue(! res.getBool());
  // }

    public void testEnvironment() {
        BetterEnv e = BetterEnv.empty();
        e.putValue("x", FInt.make(7));
        e.bless();
        BetterEnv s = new BetterEnv(e, "s");
        s.bless();
        // Copy inherits outer, shadowing is not allowed.
        s.putValueUnconditionally("x", FInt.make(9));
        assertTrue(e.getValue("x").getInt() == 7);
        assertEquals(9, s.getValue("x").getInt());
    }

    public static Test suite() {
        return new TestSuite(EvaluatorJUTest.class);
    }

    public static void main(String args[]) {
        junit.textui.TestRunner.run(suite());
    }

    /* (non-Javadoc)
     * @see junit.framework.TestCase#setUp()
     */
    @Override
    protected void setUp() throws Exception {
        Init.initializeEverything();
        // We need to initialize the transactional memory stuff before we
 // access any reference cells.
    }
}