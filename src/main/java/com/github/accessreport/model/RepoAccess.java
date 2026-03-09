package com.github.accessreport.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
// Internal domain model that pairs a repository with the list of collaborators who have access to it. This is the raw data structure we get from GitHub for each repository, and we use it as an intermediate step in building the AccessReport. It contains all the information about which users have access to a specific repository, along with their permission levels and other metadata.
public class RepoAccess {

    // The repository this record describes access for
    private Repository repository;
    private List<Collaborator> collaborators;
}
