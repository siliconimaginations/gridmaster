package com.gridmaster.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface NetworkSnapshotJpaRepository : JpaRepository<NetworkSnapshotEntity, String>
