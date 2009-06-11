(*******************************************************************************
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
 ******************************************************************************)

api SyntaxTest

  import FortressAst.{...}
  import FortressAstUtil.{...}
  import FortressSyntax.{Expression}
  import List.{...}

  grammar Helloworld extends { Expression }
      Expr |Expr:=
        Fortress is very? a:cool# b:, indeed Verys* c:cool
        do
          ids: List[\Id\] = emptyList[\Id\](1)
          ids1: List[\Id\] = ids.addRight(Id(Nothing[\APIName\], "FortressLibrary"))
          apiName:APIName = APIName1(ids1)
          name:Id = Id(Just[\APIName\](apiName), "print")
          exprs: List[\Expr\] = emptyList[\Expr\](2)
          exprs1: List[\Expr\] = exprs.addRight(FnRef( 0, Id(Nothing[\APIName\], "fn"), <| name |> , emptyList[\StaticArg\]()) asif Expr)
          es:List[\Expr\] = if v <- very then
                                       exprs1.addRight(StringLiteralExpr(Fortress.in_text " " is.in_text " " v.in_text " " a.in_text b.in_text " " indeed.in_text " " Verys " " c.in_text) asif Expr)
                                     else
                                       exprs1.addRight(StringLiteralExpr(Fortress.in_text " " is.in_text " " a.in_text b.in_text " " indeed.in_text " " Verys " " c.in_text " ") asif Expr)
                                     end
          (* Using LooseJuxt causes some wierd interpeter error of not being able to find
           * the right overloading to use.
           *)
          LooseJuxt1(es)
          (* LooseJuxt1(es) *)

        end

      Verys :Expr:=
        SPACE a:very <[ a ]>
  end

end
