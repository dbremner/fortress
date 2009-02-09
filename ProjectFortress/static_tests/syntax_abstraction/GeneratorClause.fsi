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

api GeneratorClause
    import FortressAst.{...}
    import FortressSyntax.{...}

    grammar L extends {Expression, Identifier}
        Expr |:=
            a:foobar {e:Expr ,? SPACE}* =>
            case e of
                Empty => <[ println 0 ]>
                Cons(fs,bs) =>
                    <[
                    do
                        for x <- 0#(fs asif ZZ32) do
                            println x
                            (foobar bs**)
                        end
                    end
                ]>
            end
       |  a:goobar {e:Expr ,? SPACE}* =>
            case e of
                Empty => <[ println "Empty" ]>
                Cons(fs,bs) =>
                    <[
                    do
                        var t: Boolean = fs
                        while x <- t do
                            t := false
                            println "Cons"
                            (goobar bs**)
                        end
                    end
                ]>
            end
        |  a:moobar {e:Expr ,? SPACE}* =>
            case e of
                Empty => <[ 0 ]>
                Cons(fs,bs) =>
                    <[
                    do
                        if x <- fs
                        then 1 + (moobar bs**)
                        else (moobar bs**)
                        end
                    end
                ]>
            end

    end
end
