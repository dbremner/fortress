(*******************************************************************************
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
 ******************************************************************************)

api SyntaxExtends

  import FortressAst.{...}
  import FortressSyntax.{...}
  import List.{...}

  grammar A extends { Declaration, D }
    Decl |List[\Decl\]:=
      some Thing 
        do
          fnDef : FnDef = FnDef(emptyList[\Modifier\](), 
                     Id(Nothing[\APIName\], "some" Thing.in_text),
                     emptyList[\StaticParam\](),
                     emptyList[\Param\](),
                     Nothing[\Type\],
                     Nothing[\List[\BaseType\]\],
                     WhereClause(emptyList[\WhereBinding\](), emptyList[\WhereConstraint\]()),
                     Contract(Nothing[\List[\Expr\]\], Nothing[\List[\EnsuresClause\]\], Nothing[\List[\Expr\]\]),
                     "foo",
                     StringLiteralExpr("some " Thing.in_text))
          ls : List[\FnDef\] = emptyList[\FnDef\](1)
          ls.addRight(fnDef)
        end
  end

  grammar B extends { C1, C2 }
    Thing |Expr:=
      thingB1 <[ thingB1 ]>
  end

  grammar C
    Thing :Expr:=
      thingC0 <[ thingC0 ]>
  end

  grammar D extends { Expression, C }
    Expr |Expr:=
      Thing do Thing.in_text end

    Bar :Expr:=
      Expr do Expr end
  end

  grammar C1 extends C
    Thing |Expr:=
      thingC1 <[ thingC1 ]>

    Gnu :Expr:= 
      Thing do Thing.in_text end

  end

  grammar C2 extends C
    Thing |Expr:=
      thingC2 <[ thingC2 ]>
  end

  grammar E
    Thing :Expr:=
      Foo <[ Foo ]>
  end

  grammar F extends C
    Thing |Expr:=
      thingF <[ thingF ]>
  end

end
