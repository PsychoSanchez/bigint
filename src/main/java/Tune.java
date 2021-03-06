/*
 * Copyright (c) 2013, Timothy Buktu
 * 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * 
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Determines thresholds for the Schoenhage-Strassen and Barrett algorithms.
 * Will not work for algorithms whose running time increases smoothly.
 */
public class Tune {
    private static final int START = 12;   // start at 2^12 binary digits
    private static final long MIN_DURATION_NANOSECS = 2000000000;
    private static final int ACCURACY = 1000;

    public static void main(String[] args) throws Exception {
        Method slowMethod = BigInteger.class.getDeclaredMethod("multiplyToomCook3", BigInteger.class, BigInteger.class);
        Method fastMethod = BigInteger.class.getDeclaredMethod("multiplySchoenhageStrassen", BigInteger.class, BigInteger.class, int.class);
        tune(slowMethod, fastMethod);

        System.out.println("***** Note: Barrett thresholds are only meaningful if SS thresholds");
        System.out.println("***** have been updated in BigInteger.java");
        slowMethod = BigInteger.class.getDeclaredMethod("divideBurnikelZiegler", BigInteger.class);
        fastMethod = BigInteger.class.getDeclaredMethod("divideBarrett", BigInteger.class);
        tune(slowMethod, fastMethod);
    }

    private static void tune(Method slowMethod, Method fastMethod) throws Exception {
        System.out.println("Timing " + slowMethod.getName() + " vs " + fastMethod.getName());

        slowMethod.setAccessible(true);
        fastMethod.setAccessible(true);
        int margin = slowMethod.getName().startsWith("divide") ? 10 : 0;   // fudge factor for division
        List<int[]> fastMethodIntervals = new ArrayList<int[]>();
        
        int numBitsLower = (1<<START) + 1;
        System.out.print("  Searching for first interval...");
        while (true) {
            int numBitsUpper = (numBitsLower-1) * 2 - margin;
            System.out.print(" " + numBitsUpper);
            boolean fastMethodWins = compare(slowMethod, fastMethod, numBitsUpper, true);   // compare at the upper bound
            if (!fastMethodWins)
                numBitsLower = numBitsLower*2 - 1;
            else if (numBitsLower == (1<<START)+1) {
                System.err.println("START is too high, decrease it and try again.");
                return;
            }
            else
                break;
        };
        System.out.println();

        while (true) {
            int numBitsUpper = (numBitsLower-1) * 2 - margin;

            // do a binary search for the cutover
            int searchIntervalStart = numBitsLower;
            int searchIntervalEnd = numBitsUpper;
            System.out.println("  Searching for cutover in interval [" + numBitsLower + ".." + numBitsUpper + "]:");
            System.out.print("    ");
            int searchIntervalMid;
            do {
                searchIntervalMid = (searchIntervalEnd+searchIntervalStart) / 2;
                System.out.print(" " + searchIntervalMid);
                boolean fastMethodWins = compare(slowMethod, fastMethod, searchIntervalMid, true);
                if (fastMethodWins)
                    searchIntervalEnd = searchIntervalMid;
                else
                    searchIntervalStart = searchIntervalMid;
            } while (searchIntervalEnd-searchIntervalStart >= ACCURACY);
            System.out.println();
            fastMethodIntervals.add(new int[] {searchIntervalMid, numBitsUpper});

            numBitsLower = numBitsLower*2 - 1;
            boolean fastMethodWins = compare(slowMethod, fastMethod, numBitsLower, true);
            if (fastMethodWins)
                break;
        }

        System.out.println("Intervals for which " + fastMethod.getName() + " is faster than " + slowMethod.getName() + ":");
        for (int[] interval: fastMethodIntervals) {
            System.out.print("  " + interval[0] + "..");
            boolean lastInterval = fastMethodIntervals.indexOf(interval) == fastMethodIntervals.size()-1;
            if (lastInterval)
                System.out.print("infinity");
            else
                System.out.print(interval[1]);
            System.out.print(" bits or " + interval[0]/32 + "..");
            if (lastInterval)
                System.out.print("infinity");
            else
                System.out.print(interval[1] / 32);
            System.out.println(" ints");
        }
    }

    private static boolean compare(Method method1, Method method2, int numBits, boolean warmup) throws Exception {
        long randomSeed = new Random().nextLong();

        int numIterations;
        if (warmup) {
            Random rng = new Random(randomSeed);
            numIterations = 0;
            long total = 0;
            while (total < MIN_DURATION_NANOSECS) {
                BigInteger obj = method1.getName().startsWith("divide") ? nextBigInteger(rng, 2*numBits) : nextBigInteger(rng, numBits);
                Object[] params1 = generateParams(method1, rng, numBits);
                long t1 = System.nanoTime();
                method1.invoke(obj, (Object[])params1);
                long t2 = System.nanoTime();
                total += t2 - t1;
                Object[] params2 = generateParams(method2, rng, numBits);
                t1 = System.nanoTime();
                method2.invoke(obj, (Object[])params2);
                t2 = System.nanoTime();
                total += t2 - t1;
                numIterations++;
            }
        }
        else
            numIterations = 1;

        Random rng = new Random(randomSeed);
        long method1Time = 0;
        for (int i=0; i<numIterations; i++) {
            BigInteger obj = method1.getName().startsWith("divide") ? nextBigInteger(rng, 2*numBits) : nextBigInteger(rng, numBits);
            Object[] params = generateParams(method1, rng, numBits);
            long t1 = System.nanoTime();
            method1.invoke(obj, (Object[])params);
            long t2 = System.nanoTime();
            method1Time += t2 - t1;
        }

        rng = new Random(randomSeed);
        long method2Time = 0;
        for (int i=0; i<numIterations; i++) {
            BigInteger obj = method1.getName().startsWith("divide") ? nextBigInteger(rng, 2*numBits) : nextBigInteger(rng, numBits);
            Object[] params = generateParams(method2, rng, numBits);
            long t1 = System.nanoTime();
            method2.invoke(obj, (Object[])params);
            long t2 = System.nanoTime();
            method2Time += t2 - t1;
        }
        return method2Time < method1Time;
    }

    private static Object[] generateParams(Method method, Random rng, int numBits) {
        int numParams = method.getParameterTypes().length;
        Object[] params = new Object[numParams];
        for (int j=0; j<numParams; j++)
            if (method.getParameterTypes()[j].equals(BigInteger.class))
                params[j] = nextBigInteger(rng, numBits);
            else
                params[j] = 1;   // numThreads arg for multiplySchoenhageStrassen
        return params;
    }

    private static BigInteger nextBigInteger(Random rng, int numBits) {
        BigInteger a;
        do {
            a = new BigInteger(numBits, rng);
        } while (a.bitLength() < numBits);
        return a;
    }
}