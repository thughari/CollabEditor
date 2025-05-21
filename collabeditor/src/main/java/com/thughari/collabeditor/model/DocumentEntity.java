package com.thughari.collabeditor.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;

@Data
@Document(collection = "documents")
public class DocumentEntity {

	@Id
	private String id;
	private String content;
	
	public DocumentEntity() {}
    public DocumentEntity(String id) {
        this.id = id;
        this.content = "";
    }

}
