package ar.marlbo;

import java.util.List;

import ar.marlbo.JobRunner.JobDefinition;

public record Config(String host, int port, List<JobDefinition> jobDefinitions) {
}
