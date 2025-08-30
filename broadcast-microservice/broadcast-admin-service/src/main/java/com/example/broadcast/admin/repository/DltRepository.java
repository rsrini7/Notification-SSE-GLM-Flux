package com.example.broadcast.admin.repository;

import com.example.broadcast.shared.dto.admin.DltMessage;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DltRepository extends CrudRepository<DltMessage, String> {
    
    // Derived query to get all messages sorted by failure time
    List<DltMessage> findAllByOrderByFailedAtDesc();
    
    // save, findById, deleteById, and deleteAll are all inherited from CrudRepository
}