package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.Util;
import org.deepsymmetry.beatlink.dbserver.BinaryField;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Provides information about each beat in a track: the number of milliseconds after the start of the track that
 * the beat occurs, and where the beat falls within a measure.
 *
 * @author James Elliott
 */
public class BeatGrid {

    /**
     * The unique identifier that was used to request this beat grid.
     */
    @SuppressWarnings("WeakerAccess")
    public final DataReference dataReference;

    /**
     * The message field holding the raw bytes of the beat grid as it was read over the network.
     */
    private final ByteBuffer rawData;

    /**
     * Get the raw bytes of the beat grid as it was read over the network. This can be used to analyze fields
     * that have not yet been reliably understood, and is also used for storing the beat grid in a cache file.
     * This is not available when the beat grid was loaded by Crate Digger.
     *
     * @return the bytes that make up the beat grid
     */
    @SuppressWarnings("WeakerAccess")
    public ByteBuffer getRawData() {
        if (rawData != null) {
            rawData.rewind();
            return rawData.slice();
        }
        return null;
    }

    /**
     * The number of beats in the track.
     */
    @SuppressWarnings("WeakerAccess")
    public final int beatCount;

    /**
     * Holds the reported musical count of each beat.
     */
    private final int[] beatWithinBarValues;

    /**
     * Holds the reported start time of each beat in milliseconds.
     */
    private final long[] timeWithinTrackValues;

    /**
     * Constructor for when reading from the network.
     *
     * @param reference the unique database reference that was used to request this waveform detail
     * @param message the response that contained the beat grid data
     */
    public BeatGrid(DataReference reference, Message message) {
        this(reference, ((BinaryField) message.arguments.get(3)).getValue());
    }

    /**
     * Constructor for reading from a cache file.
     *
     * @param reference the unique database reference that was used to request this waveform detail
     * @param buffer the raw bytes representing the beat grid
     */
    public BeatGrid(DataReference reference, ByteBuffer buffer) {
        dataReference = reference;
        rawData = buffer;
        final byte[] gridBytes = new byte[rawData.remaining()];
        rawData.get(gridBytes);
        beatCount = Math.max(0, (gridBytes.length - 20) / 16);  // Handle the case of an empty beat grid
        beatWithinBarValues = new int[beatCount];
        timeWithinTrackValues = new long[beatCount];
        for (int beatNumber = 0; beatNumber < beatCount; beatNumber++) {
            final int base = 20 + beatNumber * 16;  // Data for the current beat starts here
            beatWithinBarValues[beatNumber] = Util.unsign(gridBytes[base]);
            // For some reason, unlike nearly every other number in the protocol, beat timings are little-endian
            timeWithinTrackValues[beatNumber] = Util.bytesToNumberLittleEndian(gridBytes, base + 4, 4);
        }
    }

    /**
     * Helper function to find the beat grid section in a rekordbox track analysis file.
     *
     * @param anlzFile the file that was downloaded from the player
     *
     * @return the section containing the beat grid
     */
    private RekordboxAnlz.BeatGridTag findTag(RekordboxAnlz anlzFile) {
        for (RekordboxAnlz.TaggedSection section : anlzFile.sections()) {
            if (section.body() instanceof RekordboxAnlz.BeatGridTag) {
                return (RekordboxAnlz.BeatGridTag) section.body();
            }
        }
        throw new IllegalArgumentException("No beat grid found inside analysis file " + anlzFile);
    }

    /**
     * Constructor for when fetched from Crate Digger.
     *
     * @param reference the unique database reference that was used to request this waveform detail
     * @param anlzFile the parsed rekordbox track analysis file containing the waveform preview
     */
    public BeatGrid(DataReference reference, RekordboxAnlz anlzFile) {
        dataReference = reference;
        rawData = null;
        RekordboxAnlz.BeatGridTag tag = findTag(anlzFile);
        beatCount = (int)tag.lenBeats();
        beatWithinBarValues = new int[beatCount];
        timeWithinTrackValues = new long[beatCount];
        for (int beatNumber = 0; beatNumber < beatCount; beatNumber++) {
            RekordboxAnlz.BeatGridBeat beat = tag.beats().get(beatNumber);
            beatWithinBarValues[beatNumber] = beat.beatNumber();
            timeWithinTrackValues[beatNumber] = beat.time();
        }
    }

    /**
     * Constructor for use by an external cache system.
     *
     * @param reference the unique database reference that was used to request this waveform detail
     * @param beatWithinBarValues the musical time on which each beat in the grid falls
     * @param timeWithinTrackValues the time, in milliseconds, at which each beat occurs in the track
     */
    public BeatGrid(DataReference reference, int[] beatWithinBarValues, long[] timeWithinTrackValues) {
        dataReference = reference;
        rawData = null;
        beatCount = beatWithinBarValues.length;
        if (beatCount != timeWithinTrackValues.length) {
            throw new IllegalArgumentException("Arrays must contain the same number of beats.");
        }
        this.beatWithinBarValues = new int[beatCount];
        System.arraycopy(beatWithinBarValues, 0, this.beatWithinBarValues, 0, beatCount);
        this.timeWithinTrackValues = new long[beatCount];
        System.arraycopy(timeWithinTrackValues, 0, this.timeWithinTrackValues, 0, beatCount);
    }

    /**
     * Calculate where within the beat grid array the information for the specified beat can be found.
     * Yes, this is a super simple calculation; the main point of the method is to provide a nice exception
     * when the beat is out of bounds.
     *
     * @param beatNumber the beat desired
     *
     * @return the offset of the start of our cache arrays for information about that beat
     */
    private int beatOffset(int beatNumber) {
        if (beatCount == 0) {
            throw new IllegalStateException("There are no beats in this beat grid.");
        }
        if (beatNumber < 1 || beatNumber > beatCount) {
            throw new IndexOutOfBoundsException("beatNumber (" + beatNumber + ") must be between 1 and " + beatCount);
        }
        return beatNumber - 1;
    }

    /**
     * Returns the time at which the specified beat falls within the track. Beat 0 means we are before the
     * first beat (e.g. ready to play the track), so we return 0.
     *
     * @param beatNumber the beat number desired, must fall within the range 1..beatCount
     *
     * @return the number of milliseconds into the track at which the specified beat occurs
     *
     * @throws IllegalArgumentException if {@code number} is less than 0 or greater than {@code beatCount}
     */
    public long getTimeWithinTrack(int beatNumber) {
        if (beatNumber == 0) {
            return 0;
        }
        return timeWithinTrackValues[beatOffset(beatNumber)];
    }

    /**
     * Returns the musical count of the specified beat, represented by <i>B<sub>b</sub></i> in Figure 11 of
     * the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     * A number from 1 to 4, where 1 is the down beat, or the start of a new measure.
     *
     * @param beatNumber the number of the beat of interest, must fall within the range 1..beatCount
     *
     * @return where that beat falls in a bar of music
     *
     * @throws IllegalArgumentException if {@code number} is less than 1 or greater than {@code beatCount}
     */
    public int getBeatWithinBar(int beatNumber) {
        return beatWithinBarValues[beatOffset(beatNumber)];
    }

    /**
     * Finds the beat in which the specified track position falls.
     *
     * @param milliseconds how long the track has been playing
     *
     * @return the beat number represented by that time, or -1 if the time is before the first beat
     */
    @SuppressWarnings("WeakerAccess")
    public int findBeatAtTime(long milliseconds) {
        int found = Arrays.binarySearch(timeWithinTrackValues, milliseconds);
        if (found >= 0) {  // An exact match, just change 0-based array index to 1-based beat number
            return found + 1;
        } else if (found == -1) {  // We are before the first beat
            return found;
        } else {  // We are after some beat, report its beat number
            return -(found + 1);
        }
    }


    @Override
    public String toString() {
        return "BeatGrid[dataReference:" + dataReference + ", beats:" + beatCount + "]";
    }
}
