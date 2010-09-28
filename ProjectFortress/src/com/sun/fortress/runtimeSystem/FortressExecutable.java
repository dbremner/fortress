/*******************************************************************************
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
 ******************************************************************************/

package com.sun.fortress.runtimeSystem;

import com.sun.fortress.interpreter.evaluator.tasks.FortressTaskRunnerGroup;
import com.sun.fortress.nativeHelpers.systemHelper;
import jsr166y.RecursiveAction;

/** Superclass of the generated component class.  We can't refer to
 *  that one until we have defined it and we need to pass an instance
 *  of it to the primordial task.  We need a run method here because
 *  the one in the generated class isn't visisble yet.
 */
public abstract class FortressExecutable extends RecursiveAction {
    public static final int numThreads = getNumThreads();
    public static final int defaultSpawnThreshold = 5;
    public static final int spawnThreshold = getSpawnThreshold();
    public static final FortressTaskRunnerGroup group =
        new FortressTaskRunnerGroup(numThreads);
    public static final boolean useHelpJoin = getHelpJoin();

    static int getNumThreads() {
        String numThreadsString = System.getenv("FORTRESS_THREADS");
        if (numThreadsString != null) return Integer.parseInt(numThreadsString);
        else {
            int availThreads = Runtime.getRuntime().availableProcessors();
            if (availThreads <= 2) return availThreads;
            else return (int) Math.floor((double) availThreads / 2.0);
        }
    }

    static int getSpawnThreshold() {
        String spawnThresholdString = System.getenv("FORTRESS_SPAWN_THRESHOLD");
        if (spawnThresholdString != null) return Integer.parseInt(spawnThresholdString);
        return defaultSpawnThreshold;
    }

    static boolean getHelpJoin() {
        String getHelpJoinString = System.getenv("FORTRESS_HELP_JOIN");
        return envToBoolean(getHelpJoinString);
    }

    private static boolean envToBoolean(String s) {
        return s != null && s.length() > 0 &&
               s.substring(0,1).matches("[TtYy]");
    }

    public final void runExecutable(String args[]) {
        try {
            systemHelper.registerArgs(args);
            //group.invoke(this);
            group.execute(this);
            this.join();
        } finally {
            String printOnOutput = System.getenv("FORTRESS_THREAD_STATISTICS");
            if (envToBoolean(printOnOutput)) {
                System.err.println("numThreads = " + numThreads +
                                   ", spawnThreshold = " + spawnThreshold +
                                   " helpJoin = " + useHelpJoin);
                System.err.println("activeThreads = " + group.getActiveThreadCount());
                System.err.println(group);
            }
        }
    }

    /**
     * Should simply call through to static run() method in the
     * implementing class.  run() used to be an abstract method here,
     * but that requires us to make it non-static and thus totally
     * unlike every single other top-level function that we codegen.
     */
    public abstract void compute();

}
