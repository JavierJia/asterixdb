/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.transaction.management.service.logging;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

import org.apache.asterix.common.exceptions.ACIDException;
import org.apache.asterix.common.transactions.ILogManager;
import org.apache.asterix.common.transactions.ILogReader;
import org.apache.asterix.common.transactions.ILogRecord;
import org.apache.asterix.common.transactions.ILogRecord.RecordReadStatus;
import org.apache.asterix.common.transactions.LogRecord;
import org.apache.asterix.common.transactions.MutableLong;
import org.apache.asterix.common.transactions.TxnLogFile;

/**
 * NOTE: Many method calls of this class are not thread safe.
 * Be very cautious using it in a multithreaded context.
 */
public class LogReader implements ILogReader {

    public static final boolean IS_DEBUG_MODE = false;//true
    private static final Logger LOGGER = Logger.getLogger(LogReader.class.getName());
    private final ILogManager logMgr;
    private final long logFileSize;
    private final int logPageSize;
    private final MutableLong flushLSN;
    private final boolean isRecoveryMode;
    private final ByteBuffer readBuffer;
    private final ILogRecord logRecord;
    private long readLSN;
    private long bufferBeginLSN;
    private long fileBeginLSN;
    private TxnLogFile logFile;

    private enum ReturnState {
        FLUSH,
        EOF
    };

    public LogReader(ILogManager logMgr, long logFileSize, int logPageSize, MutableLong flushLSN,
            boolean isRecoveryMode) {
        this.logMgr = logMgr;
        this.logFileSize = logFileSize;
        this.logPageSize = logPageSize;
        this.flushLSN = flushLSN;
        this.isRecoveryMode = isRecoveryMode;
        this.readBuffer = ByteBuffer.allocate(logPageSize);
        this.logRecord = new LogRecord();
    }

    @Override
    public void initializeScan(long beginLSN) throws ACIDException {
        readLSN = beginLSN;
        if (waitForFlushOrReturnIfEOF() == ReturnState.EOF) {
            return;
        }
        getLogFile();
        fillLogReadBuffer();
    }

    /**
     * Get the next log record from the log file.
     *
     * @return A deserialized log record, or null if we have reached the end of the file.
     * @throws ACIDException
     */
    @Override
    public ILogRecord next() throws ACIDException {
        if (waitForFlushOrReturnIfEOF() == ReturnState.EOF) {
            return null;
        }
        if (readBuffer.position() == readBuffer.limit()) {
            boolean hasRemaining = refillLogReadBuffer();
            if (!hasRemaining && isRecoveryMode && readLSN < flushLSN.get()) {
                LOGGER.severe("Transaction log ends before expected. Log files may be missing.");
                return null;
            }
        }
        ByteBuffer readBuffer = this.readBuffer;
        boolean refilled = false;

        while (true) {
            RecordReadStatus status = logRecord.readLogRecord(readBuffer);
            switch (status) {
                case TRUNCATED: {
                    if (!refilled) {
                        //we may have just read off the end of the buffer, so try refiling it
                        if (!refillLogReadBuffer()) {
                            return null;
                        }
                        refilled = true;
                        //now see what we have in the refilled buffer
                        continue;
                    } else {
                        LOGGER.info("Log file has truncated log records.");
                        return null;
                    }
                }
                case LARGE_RECORD: {
                    readBuffer = ByteBuffer.allocate(logRecord.getLogSize());
                    fillLogReadBuffer(logRecord.getLogSize(), readBuffer);
                    //now see what we have in the expanded buffer
                    continue;
                }
                case BAD_CHKSUM: {
                    LOGGER.severe(
                            "Transaction log contains corrupt log records (perhaps due to medium error). Stopping recovery early.");
                    return null;
                }
                case OK:
                    break;
                default:
                    throw new IllegalStateException("Unexpected log read status: " + status);

            }
            // break the loop by default
            break;
        }
        logRecord.setLSN(readLSN);
        readLSN += logRecord.getLogSize();
        return logRecord;
    }

    private ReturnState waitForFlushOrReturnIfEOF() {
        synchronized (flushLSN) {
            while (readLSN >= flushLSN.get()) {
                if (isRecoveryMode) {
                    return ReturnState.EOF;
                }
                try {
                    if (IS_DEBUG_MODE) {
                        LOGGER.info(
                                "waitForFlushOrReturnIfEOF()| flushLSN: " + flushLSN.get() + ", readLSN: " + readLSN);
                    }
                    flushLSN.wait();
                } catch (InterruptedException e) {
                    //ignore
                }
            }
            return ReturnState.FLUSH;
        }
    }

    /**
     * Continues log analysis between log file splits.
     *
     * @return true if log continues, false if EOF
     * @throws ACIDException
     */
    private boolean refillLogReadBuffer() throws ACIDException {
        try {
            if (readLSN % logFileSize == logFile.size()) {
                logFile.close();
                readLSN += logFileSize - (readLSN % logFileSize);
                getLogFile();
            }
            return fillLogReadBuffer();
        } catch (IOException e) {
            throw new ACIDException(e);
        }
    }

    /**
     * Fills the log buffer with data from the log file at the current position
     *
     * @return false if EOF, true otherwise
     * @throws ACIDException
     */

    private boolean fillLogReadBuffer() throws ACIDException {
        return fillLogReadBuffer(logPageSize, readBuffer);
    }

    private boolean fillLogReadBuffer(int readSize, ByteBuffer readBuffer) throws ACIDException {
        int size = 0;
        int read = 0;
        readBuffer.position(0);
        readBuffer.limit(readSize);
        try {
            logFile.position(readLSN % logFileSize);
            //We loop here because read() may return 0, but this simply means we are waiting on IO.
            //Therefore we want to break out only when either the buffer is full, or we reach EOF.
            while (size < readSize && read != -1) {
                read = logFile.read(readBuffer);
                if (read > 0) {
                    size += read;
                }
            }
        } catch (IOException e) {
            throw new ACIDException(e);
        }
        readBuffer.position(0);
        readBuffer.limit(size);
        if (size == 0 && read == -1) {
            return false; //EOF
        }
        bufferBeginLSN = readLSN;
        return true;
    }

    //for random reading
    @Override
    public ILogRecord read(long LSN) throws ACIDException {
        readLSN = LSN;
        //wait for the log to be flushed if needed before trying to read it.
        synchronized (flushLSN) {
            while (readLSN >= flushLSN.get()) {
                try {
                    flushLSN.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        try {
            if (logFile == null) {
                //get the log file which contains readLSN
                getLogFile();
                fillLogReadBuffer();
            } else if (readLSN < fileBeginLSN || readLSN >= fileBeginLSN + logFile.size()) {
                //log is not in the current log file
                logFile.close();
                getLogFile();
                fillLogReadBuffer();
            } else if (readLSN < bufferBeginLSN || readLSN >= bufferBeginLSN + readBuffer.limit()) {
                //log is not in the current read buffer
                fillLogReadBuffer();
            } else {
                //log is either completely in the current read buffer or truncated
                readBuffer.position((int) (readLSN - bufferBeginLSN));
            }
        } catch (IOException e) {
            throw new ACIDException(e);
        }

        ByteBuffer readBuffer = this.readBuffer;
        while (true) {
            RecordReadStatus status = logRecord.readLogRecord(readBuffer);
            switch (status) {
                case LARGE_RECORD: {
                    readBuffer = ByteBuffer.allocate(logRecord.getLogSize());
                    fillLogReadBuffer(logRecord.getLogSize(), readBuffer);
                    //now see what we have in the refilled buffer
                    continue;
                }
                case TRUNCATED: {
                    if (!fillLogReadBuffer()) {
                        throw new IllegalStateException(
                                "Could not read LSN(" + LSN + ") from log file id " + logFile.getLogFileId());
                    }
                    //now read the complete log record
                    continue;
                }
                case BAD_CHKSUM: {
                    throw new ACIDException("Log record has incorrect checksum");
                }
                case OK:
                    break;
                default:
                    throw new IllegalStateException("Unexpected log read status: " + status);
            }
            break;
        }
        logRecord.setLSN(readLSN);
        readLSN += logRecord.getLogSize();
        return logRecord;
    }

    private void getLogFile() throws ACIDException {
        try {
            logFile = logMgr.getLogFile(readLSN);
            fileBeginLSN = logFile.getFileBeginLSN();
        } catch (IOException e) {
            throw new ACIDException(e);
        }
    }

    @Override
    public void close() throws ACIDException {
        try {
            if (logFile != null) {
                logFile.close();
            }
        } catch (IOException e) {
            throw new ACIDException(e);
        }
    }
}
