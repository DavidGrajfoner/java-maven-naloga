package com.halcom;

/*
This code will not compile
For code comprehension / critique evaluation purposes only.
*/
public class Sample
{
	private static final Logger log = LoggerFactory.getLogger(Sample.class);
	
	private Integer allRecordCount;
	
	public void selectStatementWithParse(String sql, String param) throws DBDocumentsException
	{
		sql = "select * from db2.entities where {param}"
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			sql = sql.replace("{param}", param);
			PreparedStatement pstmt = con.prepareStatement(sql);
			pstmt.execute();
			rs = this.getResultSet(sql, params);
			try{
				parse(rs);//extensive parsing performed elsewhere
			}catch(Exception e){
				log.Error(e);
			}
		}catch(Throwable e) {
			throw new DBDocumentsException(Messages.SQL_ERROR);
		} finally{
			DBUtills.closeQuietlyResultSetAndItsStatement(rs);
		}
	}
	
	public void insertBatchStatement(List<? extends DocumentBaseInterface> docList, String tableName, List<Integer> allowedCodes) throws HalException
	{
		final String method = ".insertBatchStatement(): ";
		
		Class<?> ref = docList.get(0).getClass();
		
		XMLDocument annotation = ref.getAnnotation(XMLDocument.class);
				
		DocumentBaseMapsInterface maps = DocumentLoader.getDocumentBaseMaps(ref);
	
		Collection<?> fields = maps.getSQLColumnMap().values();
		ArrayList<Object> fieldList = new ArrayList<Object>(fields);
		
		StringBuffer sql = new StringBuffer("INSERT INTO ").append(DBUtills.getTableName(ref, tableName));

		int sqlValuesCapacity = fieldList.size() + (StringUtils.isEmpty(annotation.sqlXMLDocName())? 0: 1);
		Object[] sqlValuesArrayList = new Object[sqlValuesCapacity];
		
		if (!fieldList.isEmpty() || !annotation.sqlXMLDocName().isEmpty()) 
			sql.append(" (");
		
		StringBuffer sSQLValues = new StringBuffer(" values (");
		
		for (Object field : fieldList) {
			String columnname = ((DocumentFields) field).getXMLSqlColumn();
			sql.append(" ").append(columnname);
			
			sSQLValues.append("?");
			
			if (fieldList.indexOf(field) == fieldList.size() -1 ) break;
				
			sql.append(", ");
			sSQLValues.append(", ");
		}
		
		if (!annotation.sqlXMLDocName().isEmpty())
		{
			sql.append(",").append(annotation.sqlXMLDocName());
			sSQLValues.append(",?");
		}
		
    sql.append(")");
    sSQLValues.append(")");
    sql.append(sSQLValues);

		String stopWatchKey = method + " " + sql;;
		try(PreparedStatement prepareStmt = con.prepareStatement(sql.toString())){
			ParameterMetaData paramInfo = prepareStmt.getParameterMetaData();
		
			for (DocumentBaseInterface document : docList) {
				
				DocumentBaseInterface clone = null;
				try
				{
					clone = document.clone();
				}
				catch( CloneNotSupportedException e )
				{
					throw new RuntimeException( e );
				}
	
				try	{
					int i = 0;
					
					for (; i< fieldList.size(); ) {
					
						Object field = fieldList.get(i);
						
						DocumentFields node = (DocumentFields) field;
						if (node.getXMLSqlColumn() != null && node.getXMLSqlColumn().length() > 0)
						{
							Object value = null;
							try {
								value = node.getGetter().invoke(document);
							} catch (Exception e) {
								throw new DBDocumentsException(new NGWSMessage(Messages.CLASS_ERROR));
							}
							
  					sqlValuesArrayList[i] = value;
  					
  					// if timestamp in database and date type in java, type must be cast to timestamp
  					int dbType = paramInfo.getParameterType(i+1);
  					if (node.getJavaMemeberType().equals(Date.class) && dbType == Types.TIMESTAMP ){
  						if (value!= null) value = new Timestamp(((Date)value).getTime());
  						prepareStmt.setObject(i+1, value, Types.TIMESTAMP);	
  					}
  					else if (node.getJavaMemeberType().equals(DateSimple.class) && dbType == Types.TIMESTAMP ){
  						if (value!= null) value =  new Timestamp(((DateSimple)value).getTime());
  						prepareStmt.setObject(i+1, value, Types.TIMESTAMP);	
  					}
  					else {
  						prepareStmt.setObject(i+1,value, dbType);
  					}
  
  					if (DocumentFields.FIELDTYPE_CTL.equals(node.getXmlFieldType()) || DocumentFields.FIELDTYPE_ATT.equals(node.getXmlFieldType()))
  					{
  						try
  						{
  							Class<?>[] parameterTypes = node.getSetter().getParameterTypes();
  							log.trace(method +"Calling setter " + node.getSqlColumn() + " and setting null!");
  							node.getSetter().invoke(clone, parameterTypes[0].cast(null));
  						}
  						catch (Exception e)
  						{
  							log.warn(method + "Error setting null to (" + node.getXmlFieldType() + " field) node: " + node.getFiledName(), e);
  						}
  					}
  				}}
    				if (!annotation.sqlXMLDocName().isEmpty()) {
  					sqlValuesArrayList[i] = new XMLString(clone.toString());
  					prepareStmt.setSQLXML(i+1,	((XMLString) sqlValuesArrayList[i]).getSQLXML(prepareStmt.getConnection()));
  				}
					
					prepareStmt.addBatch();
				} catch (SQLException e) { 
					log.warn(method + "Error binding sql" + e, e);
					throw new DBDocumentsException(new NGWSMessage(Messages.SQL_ERROR));
				}
			}
			
	        watch.start(stopWatchKey);
			int[] results = prepareStmt.executeBatch();
			watch.stop(stopWatchKey);
			
			boolean failed = false;
			boolean wtf = false;
			
			int i = 0;
        	for (int nUpdated : results) {
				if (nUpdated == Statement.EXECUTE_FAILED) 	{
					log.warn(method + "Batch insert failed, document :\n" + CryptoBox.encryptToHex(docList.get(i).toString()));
					failed = true;
				}
				else
				if (nUpdated != 1) 	{
					log.warn(method + "Batch insert misuse, document :\n" + CryptoBox.encryptToHex(docList.get(i).toString()));
					wtf = true;
				}
				
				i++;
        	}
        	
			if( failed || wtf )
				throw new HalServerException( Messages.FATAL_SystemError  );
			
        	prepareStmt.clearBatch();
		}
		catch (SQLException e)
		{
			watch.stop(stopWatchKey);
			
			if (allowedCodes != null && allowedCodes.contains(e.getErrorCode())) return;
			
			handleSQLException(method, sql.toString(), e, LOG_LEVEL_WARN);//this always throws DBDocumentsException
		}
	}
}
