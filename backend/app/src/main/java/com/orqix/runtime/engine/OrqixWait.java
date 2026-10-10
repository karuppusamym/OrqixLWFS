package com.orqix.runtime.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Date;

import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.context.Context;
import org.springframework.stereotype.Component;

import com.orqix.shared.json.Json;

/**
 * Expression bean {@code orqixWait} (S4 section 6, S12 section 10): the due date of a "wait until a date" step.
 * Flowable evaluates {@code ${orqixWait.until(execution, '<nodeId>')}} when the token arrives at the timer; the field
 * and the time of day are read from the step's {@code orqix:wait} extension, never from text inside the expression.
 * Dates and times are UTC. A date in the past gives a due date in the past, so the timer fires at once.
 */
@Component("orqixWait")
public class OrqixWait {

    /** Longest wait: the date may be at most this many days after today (UTC). */
    public static final int HORIZON_DAYS = 366;

    private final Json json;

    public OrqixWait(Json json) {
        this.json = json;
    }

    public Date until(DelegateExecution execution, String nodeId) {
        ExtensionElement wait = OrqixModel.requireExtension(
                OrqixModel.flowElement(execution.getProcessDefinitionId(), nodeId), "wait");
        String fieldRef = OrqixModel.attribute(wait, "untilField");
        String atTime = OrqixModel.attribute(wait, "atTime");
        if (fieldRef == null) {
            throw new IllegalStateException(nodeId + " has no date to wait for");
        }
        Object value = ProcessData.requireScalar(execution, json, fieldRef);
        LocalDate date;
        try {
            date = LocalDate.parse(value.toString());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("WF_WAIT_DATE_INVALID: " + fieldRef + " is not a calendar date");
        }
        Instant now = Context.getProcessEngineConfiguration().getClock().getCurrentTime().toInstant();
        LocalDate latest = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(HORIZON_DAYS);
        if (date.isAfter(latest)) {
            throw new IllegalStateException("WF_WAIT_DATE_TOO_FAR: the date " + date + " is more than "
                    + HORIZON_DAYS + " days away; the latest date to wait for is " + latest);
        }
        return Date.from(date.atTime(LocalTime.parse(atTime == null ? "09:00" : atTime)).toInstant(ZoneOffset.UTC));
    }
}
