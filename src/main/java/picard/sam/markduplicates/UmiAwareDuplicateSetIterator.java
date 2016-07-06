package picard.sam.markduplicates;

import com.sun.org.apache.bcel.internal.generic.DUP;
import htsjdk.samtools.DuplicateSet;
import htsjdk.samtools.DuplicateSetIterator;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.cram.encoding.NullEncoding;
import htsjdk.samtools.util.CloseableIterator;
import javafx.util.Pair;
//import org.testng.annotations.Test;
import picard.PicardException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

/**
 * Created by fleharty on 5/23/16.
 */
public class UmiAwareDuplicateSetIterator implements CloseableIterator<DuplicateSet> {
    private DuplicateSetIterator wrappedIterator;
    private Iterator<DuplicateSet> nextSetsIterator;
    private List<List<Integer>> adjacencyList = new ArrayList<>();
    private List<Integer> groups = new ArrayList<>();
    private int minEditDistance = 1;
    private int[] observedSequences;

    public UmiAwareDuplicateSetIterator(final DuplicateSetIterator wrappedIterator) {
        this.wrappedIterator = wrappedIterator;
        nextSetsIterator = Collections.emptyIterator();
        observedSequences = new int[(int) Math.pow(4.,10.)];
    }

    @Override
    public void close() {
        System.out.println("Entropy is " + calculateEntropy());
        wrappedIterator.close();
    }

    @Override
    public boolean hasNext() {
        return nextSetsIterator.hasNext() || wrappedIterator.hasNext();
    }

    @Override
    public DuplicateSet next() {
        if(!nextSetsIterator.hasNext())
            process(wrappedIterator.next());

        return nextSetsIterator.next();
    }

    // Takes a duplicate set and breaks it up into possible smaller sets according to the UMI,
    // and updates nextSetsIterator to be an iterator on that set of DuplicateSets.
    private void process(final DuplicateSet set) {

        List<SAMRecord> records = set.getRecords();

        // If any records don't have the RX attribute... don't do anything special
        for(int i = 0;i < records.size();i++) {
            if(records.get(i).getAttribute("RX") == null) {
                nextSetsIterator = Collections.singleton(set).iterator();
                return;
            } else {
                // count the sequence as being observed
                observedSequences[sequenceToInt((String) records.get(i).getAttribute("RX"))]++;
            }
        }

        // Sort records by RX tag
        Collections.sort(records, new Comparator<SAMRecord>() {
            @Override
            public int compare(final SAMRecord lhs, final SAMRecord rhs) {
                if(lhs == null || rhs == null) {
                    return 0;
                } else if(lhs.getAttribute("RX") == null || rhs.getAttribute("RX") == null) {
                    return 0;
                }
                else {
                    return ((String) lhs.getAttribute("RX")).compareTo((String) rhs.getAttribute("RX"));
                }
            }
        });

        int n = records.size();

        // Locate records that have identical UMI sequences
        List<String> uniqueObservedUMIs = new ArrayList<String>();

        uniqueObservedUMIs.add((String) records.get(0).getAttribute("RX"));
        for(int i = 1;i < n;i++) {
            // If the records differ (after sorting) we have a new duplicate set.
            if(!records.get(i).getAttribute("RX").equals(records.get(i-1).getAttribute("RX"))) {
                uniqueObservedUMIs.add((String) records.get(i).getAttribute("RX"));
            }
        }

        // Construct Adjacency List of UMIs that are close
        for(int i = 0;i < uniqueObservedUMIs.size();i++) {
            adjacencyList.add(i, new ArrayList<Integer>());
            groups.add(i, 0);
            for(int j = 0;j < uniqueObservedUMIs.size();j++) {
                if( getEditDistance(uniqueObservedUMIs.get(i), uniqueObservedUMIs.get(j)) <= minEditDistance) {
                    adjacencyList.get(i).add(j);
                }
            }
        }

        // Join Groups
        int nGroups = 0;
        for(int i = 0;i < adjacencyList.size();i++) {
            // Have I visited this yet?
            if(groups.get(i) == 0) {
                // No, I haven't yet seen this
                nGroups++; // We've now seen a new group

                // Depth first search on adjacencyList, setting all the values to group
                dfs(i, nGroups);
            }
        }

        // Figure out the number of groups
        //int maxGroups = 1;
        //for(int i = 0;i < groups.size();i++) {
        //    if(groups.get(i) > maxGroups) {
        //        maxGroups = groups.get(i);
        //    }
        //}
        int maxGroups = nGroups;

        // Construct DuplicateSetList
        List<DuplicateSet> duplicateSetList= new ArrayList<>();
        for(int i = 0;i < maxGroups;i++) {
            DuplicateSet e = new DuplicateSet();
            duplicateSetList.add(e);
        }

        // Assign each record to a group
        for(int i = 0;i < records.size();i++) {
            String umi = (String) records.get(i).getAttribute("RX");

            // Figure out which group this belongs to
            int recordGroup = groups.get(uniqueObservedUMIs.indexOf((String) umi));
            duplicateSetList.get(recordGroup-1).add(records.get(i));
        }

        // For each group, create a duplicate set and add it to the list.
//        System.out.println("Start of Original Duplicate set");
//        for(int k = 0;k < duplicateSetList.size();k++) {
//            List<SAMRecord> tmpRecords = duplicateSetList.get(k).getRecords();
//            System.out.println("Start of sub-duplicate set");
//            for (int j = 0; j < tmpRecords.size(); j++) {
//                System.out.println("Duplicate set k = " + k + " " + tmpRecords.get(j).getAttribute("RX"));
//            }
//            System.out.println();
//        }

        nextSetsIterator = duplicateSetList.iterator();
    }

    private void dfs(final int index, final int group) {
        if(groups.get(index) == 0) {
            groups.set(index, group);
            for (int i = 0; i < adjacencyList.get(index).size(); i++) {
                dfs(adjacencyList.get(index).get(i), group);
            }
        }
    }

    private int getEditDistance(String s1, String s2) {
        if(s1 == null && s2 == null) {
            return 0;
        }
        if(s1.length() != s2.length()) {
            throw new PicardException("Barcode " + s1 + " and " + s2 + " do not have matching lengths.");
        }
        int count = 0;
        for(int i = 0;i < s1.length();i++) {
            if(s1.charAt(i) != s2.charAt(i)) {
                count++;
            }
        }
        return count;
    }

    // This is a generalization of getEditDistance that ordinarily makes sense
    // to calculate the distance between two UMIs that have been encoded using
    // one-hot encoding.
    private double getEditDistance(double[][] umiMatrix1, double[][] umiMatrix2) {

        double norm = 2.0;
        if(umiMatrix1.length != umiMatrix2.length) {
            throw new PicardException("Umi matrices are not the same length, this is a bug.");
        }
        // Subtract the two matrices and calculate their distance
        int umiLength = umiMatrix1.length;

        double sum = 0.0;
        for(int i = 0;i < umiLength;i++) {
            for(int j = 0;j < 4;i++) {
                sum += Math.pow(umiMatrix1[i][j] - umiMatrix2[i][j], norm);
            }
        }

        return Math.pow(sum, 1 / norm);
    }

    // This is the kernel that is used for the mean-shift algorithm.
    // The choice of a cut-and-shifted exponential kernel is made
    // because we expect the number of errors in UMIs to follow
    // a roughly exponential (nearly Poisson) distribution.
    // The parameter lambda corresponds to the expected error rate.
    private double kernel(double r, double lambda) {
        return Math.exp(-lambda * r);
    }

    private List<DuplicateSet> meanShift(DuplicateSet duplicateSets) {
        List<DuplicateSet> ds = new ArrayList<>();

        // Construct List of UMIs in one-hot encoding
        List<double[][]> umis = new ArrayList<>();
        List<SAMRecord> records = duplicateSets.getRecords();

        for(int i = 0;i < duplicateSets.size();i++) {
            umis.add(umiToOneHot((String) records.get(i).getAttribute("RX")));
        }

        return ds;
    }

    private double[][] umiToOneHot(String umi) {
        double[][] oneHot = new double[umi.length()][];
        for(int i = 0;i < umi.length();i++) {
            oneHot[i] = new double[4];

            char currentCharacter = umi.charAt(i);
            if(currentCharacter == 'A') {
                oneHot[i][0] = 1;
            }
            if(currentCharacter == 'T') {
                oneHot[i][1] = 1;
            }
            if(currentCharacter == 'C') {
                oneHot[i][2] = 1;
            }
            if(currentCharacter == 'G') {
                oneHot[i][3] = 1;
            }
        }

        return oneHot;
    }

    private int sequenceToInt(String umi) {
        int value = 0;
        for(int i = 0;i < umi.length();i++) {
            value += baseToInt(umi.charAt(i))*4^i;
        }

        return value;

    }

    private int baseToInt(char c) {
        if(c == 'A') {
            return 0;
        } else
        if(c == 'T') {
            return 1;
        } else
        if(c == 'C') {
            return 2;
        } else
        if(c == 'G') {
            return 3;
        } else {
            return -1;
        }
    }

    private double calculateEntropy() {
        int totalObservations = 0;
        // Count total observed sequences
        for(int i = 0;i < observedSequences.length;i++) {
            totalObservations += observedSequences[i];
        }

        // Calculate entropy
        double entropy = 0.0;
        for(int i = 0;i < observedSequences.length;i++) {
            if(observedSequences[i] != 0) {
                entropy += -((double)observedSequences[i]/totalObservations)*Math.log((double)observedSequences[i]/totalObservations);
            }
        }
        entropy /= Math.log(2.0);  // Convert entorpy to bits.

        return entropy;
    }

    private String oneHotToUmi(double[][] umiMatrix) {
        return "hi";
    }
}

