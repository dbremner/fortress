(*******************************************************************************
    Copyright 2011 Sun Microsystems, Inc.,
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

api CompilerLibrary

(************************************************************
* \subsection*{Exception hierarchy}
************************************************************)
trait Exception comprises { UncheckedException, CheckedException }
end

(* Exceptions which are not checked *)

trait UncheckedException extends Exception excludes CheckedException
end

(* Checked Exceptions *)

trait CheckedException extends Exception excludes UncheckedException
end

(************************************************************
 * Value bindings
 ************************************************************)

(************************************************************
 * Simple Combinators
 ************************************************************)

ignore(_:Any):()

opr ===(a:Any, b:Any):Boolean
opr NEQV(a:Any, b:Any):Boolean

fail[\T\](s:String):()

(************************************************************
 * Simple Range support
 ************************************************************)

trait GeneratorZZ32 excludes { Boolean }
    abstract getter asString(): String
    seq(self): SeqGeneratorZZ32
    abstract loop(body:ZZ32->()): ()
    abstract generate(r: ReductionString, body: ZZ32->String): String
    abstract seqloop(body:ZZ32->()): ()
    abstract seqgenerate(r: ReductionString, body: ZZ32->String): String
    abstract filter(f: ZZ32 -> Boolean): GeneratorZZ32
    opr IN(x:ZZ32, self): Boolean
end

trait SeqGeneratorZZ32 extends GeneratorZZ32
    abstract filter(f: ZZ32 -> Boolean): SeqGeneratorZZ32
end

opr =(left:GeneratorZZ32, right:GeneratorZZ32): Boolean

__bigOperator(o:ReductionString,
              desugaredClauses:(ReductionString, String->String)->String): String

__generate(g: GeneratorZZ32, r: ReductionString, f:ZZ32->String): String
__generate(p: Boolean, r: ReductionString, f:()->String): String

__loop(g: GeneratorZZ32, body: ZZ32->()): ()

trait ReductionString
    abstract empty(): String
    abstract join(a: String, b: String): String
end

object StringConcatenation extends ReductionString
    empty(): String
    join(a: String, b: String): String
end

opr :(lo:ZZ32, hi:ZZ32): GeneratorZZ32
opr #(lo:ZZ32, sz:ZZ32): GeneratorZZ32

(*
opr BIG ||(): ReductionString
*)

(************************************************************
* Random numbers
************************************************************)

(*
random(i:RR64): RR64
randomZZ32(x:ZZ32): ZZ32
*)

end
