(*******************************************************************************
    Copyright 2011, Oracle and/or its affiliates.
    All rights reserved.


    Use is subject to license terms.

    This distribution may include materials developed by third parties.

 ******************************************************************************)

api EqualityTest9a

trait TestEquality[\Self\] comprises Self
    opr =(self, other:Self): Boolean
end

opr TESTNOTEQUAL[\T extends TestEquality[\T\]\](a: T, b: T): Boolean

end
