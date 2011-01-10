(*******************************************************************************
    Copyright 2010 Sun Microsystems, Inc.,
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

api CompilerBuiltin
import AnyType.{Any}

trait Object extends Any
end Object

(*)     getter asString(): String
(*)     getter asExprString(): String
(*)     getter asDebugString(): String


nanoTime(): RR64

trait String
(*)    coerce(n: ZZ32) 
(*)    coerce(n: ZZ64) 
    getter isEmpty(): Boolean
    getter asString(): String
    opr <(self, b:String): Boolean
    opr =(self, b: String): Boolean
    opr |self| : ZZ32
    opr || (self, b:String):String
    opr juxtaposition(self, b:String): String
    opr[i:ZZ32] : ZZ32
    substring(lo:ZZ32, hi:ZZ32):String
end

object FlatString extends String
end FlatString

println(s:String):()
(*) println(x:Object): ()
(*) println():()
(*) println(x:Any):()
println(x:ZZ32):()
println(x:ZZ64):()
(*) println(x:RR32):()
println(x:RR64):()
(*) println[\A,B\](x: (A,B)):()
(*) println[\A,B,C\](x: (A,B,C)):()
(*) println[\A,B,C,D\](x: (A,B,C,D)):()
(*) println[\A,B,C,D,E\](x: (A,B,C,D,E)):()
(*) println[\A,B,C,D,E,F\](x: (A,B,C,D,E,F)):()
(*) println[\A,B,C,D,E,F,G\](x: (A,B,C,D,E,F,G)):()

errorPrintln(s:String):()
(*) errorPrintln(x:Object): ()
(*) errorPrintln():()
(*) errorPrintln(x:Any):()
errorPrintln(x:ZZ32):()
errorPrintln(x:ZZ64):()
(*) errorPrintln(x:RR32):()
errorPrintln(x:RR64):()
(*) errorPrintln[\A,B\](x: (A,B)):()
(*) errorPrintln[\A,B,C\](x: (A,B,C)):()
(*) errorPrintln[\A,B,C,D\](x: (A,B,C,D)):()
(*) errorPrintln[\A,B,C,D,E\](x: (A,B,C,D,E)):()
(*) errorPrintln[\A,B,C,D,E,F\](x: (A,B,C,D,E,F)):()
(*) errorPrintln[\A,B,C,D,E,F,G\](x: (A,B,C,D,E,F,G)):()

strToInt(s:String):ZZ32

trait Number excludes { String }
    abstract getter asString(): String
end

trait ZZ64 extends Number excludes RR64
    coerce(x: IntLiteral)
    coerce(x: ZZ32) 
    getter asZZ32(): ZZ32 
    getter asString(): String 
    opr |self| : ZZ64
    opr -(self): ZZ64
    opr +(self, other:ZZ64): ZZ64
    opr -(self, other:ZZ64): ZZ64 
    opr <(self, other:ZZ64): Boolean 
    opr <=(self, other:ZZ64): Boolean 
    opr >(self, other:ZZ64): Boolean 
    opr >=(self, other:ZZ64): Boolean 
    opr =(self, other:ZZ64): Boolean 
    opr juxtaposition(self, other:ZZ64): ZZ64
    opr DOT(self, other:ZZ64): ZZ64 
    opr DIV(self, other:ZZ64): ZZ64
end

trait ZZ32 extends Number excludes { ZZ64, RR32, RR64 }
    coerce(x: IntLiteral)
    getter asZZ32(): ZZ32
    getter asString(): String
    opr |self| : ZZ32
    opr -(self): ZZ32
    opr +(self, other:ZZ32): ZZ32
    opr -(self, other:ZZ32): ZZ32
    opr <(self, other:ZZ32) : Boolean
    opr <=(self, other:ZZ32): Boolean
    opr >(self, other:ZZ32): Boolean
    opr >=(self, other:ZZ32): Boolean
    opr =(self, other:ZZ32): Boolean
    opr juxtaposition(self, other:ZZ32): ZZ32
    opr DOT(self, other:ZZ32): ZZ32
    opr DIV(self, other:ZZ32): ZZ32
end

trait IntLiteral excludes {ZZ32, ZZ64}
    abstract getter asZZ32(): ZZ32
    abstract getter asZZ64(): ZZ64
(*
    abstract getter asNN32(): NN32
    abstract getter asZZ(): ZZ
    abstract getter asRR32(): RR32
*)
    abstract getter asRR64(): RR64
end

trait RR64 extends Number excludes ZZ64
    coerce(x: FloatLiteral)
    coerce(x: RR32)
    getter asString(): String
    opr |self| : RR64
    opr -(self): RR64
    opr +(self, other:RR64): RR64
    opr -(self, other:RR64): RR64
    opr <(self, other:RR64): Boolean
    opr <=(self, other:RR64): Boolean
    opr >(self, other:RR64): Boolean
    opr >=(self, other:RR64): Boolean
    opr =(self, other:RR64): Boolean
    opr juxtaposition(self, other:RR64): RR64
    opr DOT(self, other:RR64): RR64
    opr /(self, other:RR64): RR64
    opr ^(self, other:RR64): RR64
    opr ^(self, other:ZZ32): RR64
end

trait RR32 extends Number excludes { ZZ64, ZZ32, RR64 }
    coerce(x: FloatLiteral)
end

trait FloatLiteral excludes {RR32, RR64}
    abstract getter asRR32(): RR32
    abstract getter asRR64(): RR64
end


trait Boolean
  getter holds(): Boolean
  getter get(): ()
  getter asString(): String
  getter asExprString(): String
  getter size(): ZZ32
  opr |self| : ZZ32

  opr NOT(self):Boolean
  opr AND(self, other:Boolean):Boolean
  opr AND(self, other:()->Boolean):Boolean
  opr OR(self, other:Boolean):Boolean
  opr OR(self, other:()->Boolean):Boolean
  opr XOR(self, other:Boolean):Boolean
  opr OPLUS(self, other:Boolean):Boolean
  opr NEQV(self, other:Boolean):Boolean
  opr EQV(self, other:Boolean):Boolean
  opr <->(self, other:Boolean):Boolean
  opr ->(self, other:Boolean):Boolean

  opr =(self, other:Boolean): Boolean
end

true: Boolean
false: Boolean

(************************************************************
* Random numbers
************************************************************)

random(i:RR64): RR64
randomZZ32(x:ZZ32): ZZ32

end
