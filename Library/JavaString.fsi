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

api JavaString

  lineSeparator: String 
  
  object JavaString extends { String }
    opr ||(self, b:JavaString): String
    opr ||(self, b:String):String
    opr ||(self, b:Char): String         
    opr ||(a:JavaString, self): String  
    javaConcat(self, b:JavaString):String
    javaConcat(self, b:Char):String
  end
    
end
