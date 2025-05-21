package com.thughari.collabeditor.repo;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.thughari.collabeditor.model.DocumentEntity;

public interface DocumentRepository  extends MongoRepository<DocumentEntity, String>{

}
