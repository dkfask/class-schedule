package com.classschedule.solver;

public class SolverDataNotReadyException extends IllegalStateException {
    public SolverDataNotReadyException(String message) {
        super(message);
    }
}
