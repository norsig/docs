package com.sismics.docs.core.dao.jpa;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.Query;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.jpa.criteria.DocumentCriteria;
import com.sismics.docs.core.dao.jpa.dto.DocumentDto;
import com.sismics.docs.core.dao.lucene.LuceneDao;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.util.AuditLogUtil;
import com.sismics.docs.core.util.jpa.PaginatedList;
import com.sismics.docs.core.util.jpa.PaginatedLists;
import com.sismics.docs.core.util.jpa.QueryParam;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.util.context.ThreadLocalContext;

/**
 * Document DAO.
 * 
 * @author bgamard
 */
public class DocumentDao {
    /**
     * Creates a new document.
     * 
     * @param document Document
     * @param userId User ID
     * @return New ID
     */
    public String create(Document document, String userId) {
        // Create the UUID
        document.setId(UUID.randomUUID().toString());
        
        // Create the document
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.persist(document);
        
        // Create audit log
        AuditLogUtil.create(document, AuditLogType.CREATE, userId);
        
        return document.getId();
    }
    
    /**
     * Returns the list of all active documents.
     * 
     * @return List of documents
     */
    @SuppressWarnings("unchecked")
    public List<Document> findAll() {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select d from Document d where d.deleteDate is null");
        return q.getResultList();
    }
    
    /**
     * Returns the list of all active documents from a user.
     * 
     * @param userId User ID
     * @return List of documents
     */
    @SuppressWarnings("unchecked")
    public List<Document> findByUserId(String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select d from Document d where d.userId = :userId and d.deleteDate is null");
        q.setParameter("userId", userId);
        return q.getResultList();
    }
    
    /**
     * Returns an active document with permission checking.
     * 
     * @param id Document ID
     * @param perm Permission needed
     * @param targetIdList List of targets
     * @return Document
     */
    public DocumentDto getDocument(String id, PermType perm, List<String> targetIdList) {
        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(id, perm, targetIdList)) {
            return null;
        }

        EntityManager em = ThreadLocalContext.get().getEntityManager();
        StringBuilder sb = new StringBuilder("select distinct d.DOC_ID_C, d.DOC_TITLE_C, d.DOC_DESCRIPTION_C, d.DOC_SUBJECT_C, d.DOC_IDENTIFIER_C, d.DOC_PUBLISHER_C, d.DOC_FORMAT_C, d.DOC_SOURCE_C, d.DOC_TYPE_C, d.DOC_COVERAGE_C, d.DOC_RIGHTS_C, d.DOC_CREATEDATE_D, d.DOC_LANGUAGE_C, ");
        sb.append(" (select count(s.SHA_ID_C) from T_SHARE s, T_ACL ac where ac.ACL_SOURCEID_C = d.DOC_ID_C and ac.ACL_TARGETID_C = s.SHA_ID_C and ac.ACL_DELETEDATE_D is null and s.SHA_DELETEDATE_D is null), ");
        sb.append(" (select count(f.FIL_ID_C) from T_FILE f where f.FIL_DELETEDATE_D is null and f.FIL_IDDOC_C = d.DOC_ID_C), ");
        sb.append(" u.USE_USERNAME_C ");
        sb.append(" from T_DOCUMENT d ");
        sb.append(" join T_USER u on d.DOC_IDUSER_C = u.USE_ID_C ");
        sb.append(" where d.DOC_ID_C = :id and d.DOC_DELETEDATE_D is null ");
       
        Query q = em.createNativeQuery(sb.toString());
        q.setParameter("id", id);

        Object[] o;
        try {
            o = (Object[]) q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
        
        DocumentDto documentDto = new DocumentDto();
        int i = 0;
        documentDto.setId((String) o[i++]);
        documentDto.setTitle((String) o[i++]);
        documentDto.setDescription((String) o[i++]);
        documentDto.setSubject((String) o[i++]);
        documentDto.setIdentifier((String) o[i++]);
        documentDto.setPublisher((String) o[i++]);
        documentDto.setFormat((String) o[i++]);
        documentDto.setSource((String) o[i++]);
        documentDto.setType((String) o[i++]);
        documentDto.setCoverage((String) o[i++]);
        documentDto.setRights((String) o[i++]);
        documentDto.setCreateTimestamp(((Timestamp) o[i++]).getTime());
        documentDto.setLanguage((String) o[i++]);
        documentDto.setShared(((Number) o[i++]).intValue() > 0);
        documentDto.setFileCount(((Number) o[i++]).intValue());
        documentDto.setCreator((String) o[i]);
        return documentDto;
    }
    
    /**
     * Deletes a document.
     * 
     * @param id Document ID
     * @param userId User ID
     */
    public void delete(String id, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
            
        // Get the document
        Query q = em.createQuery("select d from Document d where d.id = :id and d.deleteDate is null");
        q.setParameter("id", id);
        Document documentDb = (Document) q.getSingleResult();
        
        // Delete the document
        Date dateNow = new Date();
        documentDb.setDeleteDate(dateNow);

        // Delete linked data
        q = em.createQuery("update File f set f.deleteDate = :dateNow where f.documentId = :documentId and f.deleteDate is null");
        q.setParameter("documentId", id);
        q.setParameter("dateNow", dateNow);
        q.executeUpdate();
        
        q = em.createQuery("update Acl a set a.deleteDate = :dateNow where a.sourceId = :documentId and a.deleteDate is null");
        q.setParameter("documentId", id);
        q.setParameter("dateNow", dateNow);
        q.executeUpdate();
        
        q = em.createQuery("update DocumentTag dt set dt.deleteDate = :dateNow where dt.documentId = :documentId and dt.deleteDate is not null");
        q.setParameter("documentId", id);
        q.setParameter("dateNow", dateNow);
        q.executeUpdate();
        
        q = em.createQuery("update Relation r set r.deleteDate = :dateNow where (r.fromDocumentId = :documentId or r.toDocumentId = :documentId) and r.deleteDate is not null");
        q.setParameter("documentId", id);
        q.setParameter("dateNow", dateNow);
        q.executeUpdate();
        
        // Create audit log
        AuditLogUtil.create(documentDb, AuditLogType.DELETE, userId);
    }
    
    /**
     * Gets an active document by its ID.
     * 
     * @param id Document ID
     * @return Document
     */
    public Document getById(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery("select d from Document d where d.id = :id and d.deleteDate is null");
        q.setParameter("id", id);
        try {
            return (Document) q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
    
    /**
     * Searches documents by criteria.
     * 
     * @param paginatedList List of documents (updated by side effects)
     * @param criteria Search criteria
     * @param sortCriteria Sort criteria
     * @throws Exception
     */
    public void findByCriteria(PaginatedList<DocumentDto> paginatedList, DocumentCriteria criteria, SortCriteria sortCriteria) throws Exception {
        Map<String, Object> parameterMap = new HashMap<>();
        List<String> criteriaList = new ArrayList<>();
        
        StringBuilder sb = new StringBuilder("select distinct d.DOC_ID_C c0, d.DOC_TITLE_C c1, d.DOC_DESCRIPTION_C c2, d.DOC_CREATEDATE_D c3, d.DOC_LANGUAGE_C c4, ");
        sb.append(" (select count(s.SHA_ID_C) from T_SHARE s, T_ACL ac where ac.ACL_SOURCEID_C = d.DOC_ID_C and ac.ACL_TARGETID_C = s.SHA_ID_C and ac.ACL_DELETEDATE_D is null and s.SHA_DELETEDATE_D is null) c5, ");
        sb.append(" (select count(f.FIL_ID_C) from T_FILE f where f.FIL_DELETEDATE_D is null and f.FIL_IDDOC_C = d.DOC_ID_C) c6 ");
        sb.append(" from T_DOCUMENT d ");
        
        // Add search criterias
        if (criteria.getTargetIdList() != null) {
            // Read permission is enough for searching
            sb.append(" left join T_ACL a on a.ACL_TARGETID_C in (:targetIdList) and a.ACL_SOURCEID_C = d.DOC_ID_C and a.ACL_PERM_C = 'READ' and a.ACL_DELETEDATE_D is null ");
            sb.append(" left join T_DOCUMENT_TAG dta on dta.DOT_IDDOCUMENT_C = d.DOC_ID_C and dta.DOT_DELETEDATE_D is null ");
            sb.append(" left join T_ACL a2 on a2.ACL_TARGETID_C in (:targetIdList) and a2.ACL_SOURCEID_C = dta.DOT_IDTAG_C and a2.ACL_PERM_C = 'READ' and a2.ACL_DELETEDATE_D is null ");
            criteriaList.add("(a.ACL_ID_C is not null or a2.ACL_ID_C is not null)");
            parameterMap.put("targetIdList", criteria.getTargetIdList());
        }
        if (!Strings.isNullOrEmpty(criteria.getSearch()) || !Strings.isNullOrEmpty(criteria.getFullSearch())) {
            LuceneDao luceneDao = new LuceneDao();
            Set<String> documentIdList = luceneDao.search(criteria.getSearch(), criteria.getFullSearch());
            if (documentIdList.size() == 0) {
                // If the search doesn't find any document, the request should return nothing
                documentIdList.add(UUID.randomUUID().toString());
            }
            criteriaList.add("d.DOC_ID_C in :documentIdList");
            parameterMap.put("documentIdList", documentIdList);
        }
        if (criteria.getCreateDateMin() != null) {
            criteriaList.add("d.DOC_CREATEDATE_D >= :createDateMin");
            parameterMap.put("createDateMin", criteria.getCreateDateMin());
        }
        if (criteria.getCreateDateMax() != null) {
            criteriaList.add("d.DOC_CREATEDATE_D <= :createDateMax");
            parameterMap.put("createDateMax", criteria.getCreateDateMax());
        }
        if (criteria.getTagIdList() != null && !criteria.getTagIdList().isEmpty()) {
            int index = 0;
            for (String tagId : criteria.getTagIdList()) {
                sb.append(String.format(" join T_DOCUMENT_TAG dt%d on dt%d.DOT_IDDOCUMENT_C = d.DOC_ID_C and dt%d.DOT_IDTAG_C = :tagId%d and dt%d.DOT_DELETEDATE_D is null ", index, index, index, index, index));
                parameterMap.put("tagId" + index, tagId);
                index++;
            }
        }
        if (criteria.getShared() != null && criteria.getShared()) {
            criteriaList.add("(select count(s.SHA_ID_C) from T_SHARE s, T_ACL ac where ac.ACL_SOURCEID_C = d.DOC_ID_C and ac.ACL_TARGETID_C = s.SHA_ID_C and ac.ACL_DELETEDATE_D is null and s.SHA_DELETEDATE_D is null) > 0");
        }
        if (criteria.getLanguage() != null) {
            criteriaList.add("d.DOC_LANGUAGE_C = :language");
            parameterMap.put("language", criteria.getLanguage());
        }
        if (criteria.getCreatorId() != null) {
            criteriaList.add("d.DOC_IDUSER_C = :creatorId");
            parameterMap.put("creatorId", criteria.getCreatorId());
        }
        
        criteriaList.add("d.DOC_DELETEDATE_D is null");
        
        if (!criteriaList.isEmpty()) {
            sb.append(" where ");
            sb.append(Joiner.on(" and ").join(criteriaList));
        }
        
        // Perform the search
        QueryParam queryParam = new QueryParam(sb.toString(), parameterMap);
        List<Object[]> l = PaginatedLists.executePaginatedQuery(paginatedList, queryParam, sortCriteria);
        
        // Assemble results
        List<DocumentDto> documentDtoList = new ArrayList<>();
        for (Object[] o : l) {
            int i = 0;
            DocumentDto documentDto = new DocumentDto();
            documentDto.setId((String) o[i++]);
            documentDto.setTitle((String) o[i++]);
            documentDto.setDescription((String) o[i++]);
            documentDto.setCreateTimestamp(((Timestamp) o[i++]).getTime());
            documentDto.setLanguage((String) o[i++]);
            documentDto.setShared(((Number) o[i++]).intValue() > 0);
            documentDto.setFileCount(((Number) o[i]).intValue());
            documentDtoList.add(documentDto);
        }

        paginatedList.setResultList(documentDtoList);
    }

    /**
     * Update a document.
     * 
     * @param document Document to update
     * @param userId User ID
     * @return Updated document
     */
    public Document update(Document document, String userId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        
        // Get the document
        Query q = em.createQuery("select d from Document d where d.id = :id and d.deleteDate is null");
        q.setParameter("id", document.getId());
        Document documentFromDb = (Document) q.getSingleResult();
        
        // Update the document
        documentFromDb.setTitle(document.getTitle());
        documentFromDb.setDescription(document.getDescription());
        documentFromDb.setSubject(document.getSubject());
        documentFromDb.setIdentifier(document.getIdentifier());
        documentFromDb.setPublisher(document.getPublisher());
        documentFromDb.setFormat(document.getFormat());
        documentFromDb.setSource(document.getSource());
        documentFromDb.setType(document.getType());
        documentFromDb.setCoverage(document.getCoverage());
        documentFromDb.setRights(document.getRights());
        documentFromDb.setCreateDate(document.getCreateDate());
        documentFromDb.setLanguage(document.getLanguage());
        
        // Create audit log
        AuditLogUtil.create(documentFromDb, AuditLogType.UPDATE, userId);
        
        return documentFromDb;
    }
}
