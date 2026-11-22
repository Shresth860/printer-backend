package com.bizbitenow.printservice.common;

public enum JobStatus {
    QUEUED, SENT_TO_PRINTER, PRINTED_CONFIRMED, FAILED, CANCELLED,
    /** Request and filtering aliases retained for existing clients. */
    PENDING, SENT_TO_DEVICE, DISPATCHED, PRINTING, SUCCESS
}
