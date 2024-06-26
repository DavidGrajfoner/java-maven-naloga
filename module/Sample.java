package com.halcom;

/*
This code will not compile
For code comprehension / critique evaluation purposes only.
*/
public class Sample
{
	private static final Logger log = LoggerFactory.getLogger(Sample.class);

	// Nepotrebna variabla, se ne uporabi nikjer
	private Integer allRecordCount;

	/* Kritika in težave:
	   Metoda sestavlja SQL poizvedbo z združevanjem nizov (sql = sql.replace("{param}", param);). Ta pristop je ranljiv za SQL injection, če param vsebuje zlonamerno SQL kodo.
	   Nepotrebna deklaracija PreparedStatement pstmt znotraj try bloka
	   Zapre ResultSet (DBUtills.closeQuietlyResultSetAndItsStatement(rs);), vendar ne zapre pravilno PreparedStatement
	   Zajame Throwable, kar je preveč splošno. To zajame tudi Error primere, ki so običajno nepopravljivi */
	public void selectStatementWithParse(String sql, String param) throws DBDocumentsException {
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

	/* SQL Injection - Uporabimo PreparedStatement s paremetri namesto neposrednega vstavljanja vrednosti v poizvedbo
	   Uporaba Try-With-Resource - Zamenjamo ročno zapiranje PreparedStatement in ResultSet z uporabo try-with-resource za samodejno upravljanje virov
	   Pravilno obseganje spremenljivk - Odstranimo odvečne deklaracije spremenljivk sql in pstmt znotraj metode
	   Dosledno beleženje - Uporaba pravilne konvencije beleženja (log.error namesto log.Error) za boljšo jasnost in spoštovanje Java standardov
	   Obravnave napak - Izboljšanje obravnave napak, da zajamemo specifične izjeme (SQLException in potencialno IOException, če metoda parse vrže to izjemo) */
	public void selectStatementWithParseCorrected(String param) throws DBDocumentsException {
		String sql = "select * from db2.entities where some_column = ?";
		try (PreparedStatement pstmt = con.prepareStatement(sql)) {
			pstmt.setString(1, param);
			try (ResultSet rs = pstmt.executeQuery()) {
				parse(rs); //extensive parsing performed elsewhere
			} catch (IOException | ParseException e) {
				log.error("Error parsing ResultSet", e);
				// Obravnava specifičnih izjem za napake, če je potrebno
			}
		} catch (SQLException e) {
			log.error("Error executing SQL query", e);
			throw new DBDocumentsException(Messages.SQL_ERROR);
		}
	}

	/* Kritika in težave:
	   Slaba struktura in nepreglednost - Metoda je dolga in težko berljiva zaradi pomanjkanja modularizacije. Večina logike je vgrajena v eni veliki metodi, kar otežuje razumevanje in vzdrževanje
	   Nepotreben uporabniški vmesnik - Uporaba StringBuffer za gradnjo SQL poizvedbe, kar je manj učinkovito kot uporaba StringBuilder v enonitnih operacijah
	   Pomanjkljivo upravljanje izjem - Nepopolna obravnava in beleženje izjem, kar lahko povzroči težave pri odpravljanju napak in diagnosticiranju težav
	   Pomanjkanje preverjanja predpogojev - Metoda ne preverja vnaprej, ali so vsi pogoji izpolnjeni (npr. ali je docList prazen), kar lahko povzroči nepričakovano obnašanje ali napake med izvajanjem
	   Pogosta ponovna uporaba kode  */
	public void insertBatchStatement(List<? extends DocumentBaseInterface> docList, String tableName, List<Integer> allowedCodes) throws HalException {
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
								prepareStmt.setObject(i+1, value, dbType);
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

	/* Dodano preverjanje za null ali praznosti docList, da zagotovimo, da vsebuje dokumente
	   Namesto `StringBuffer` uporabljen `StringBuilder` za operacije v eni niti za boljšo učinkovitost.
	   Modularizacija gradnje SQL poizvedb za izboljšano berljivost in vzdržljivost.
	   Uporaba try-with-resources za `PreparedStatement` za zagotovitev pravilnega upravljanja virov.
	   Ločene metode za zmanjšanje podvajanja kode ter izboljšanje berljivosti in vzdržljivosti
	   Dodana podrobna obravnava napak in beleženje */
	private void insertBatchStatementCorrected(List<? extends DocumentBaseInterface> docList, String tableName, List<Integer> allowedCodes) throws HalException {
		final String method = ".insertBatchStatement(): ";

		if (docList == null || docList.isEmpty()) {
			throw new IllegalArgumentException("Document list cannot be null or empty");
		}

		Class<?> ref = docList.get(0).getClass();
		XMLDocument annotation = ref.getAnnotation(XMLDocument.class);
		DocumentBaseMapsInterface maps = DocumentLoader.getDocumentBaseMaps(ref);
		List<DocumentFields> fieldList = new ArrayList<>(maps.getSQLColumnMap().values());

		String sql = buildSqlInsertStatement(tableName, annotation, fieldList);
		String stopWatchKey = method + " " + sql;

		try (PreparedStatement prepareStmt = con.prepareStatement(sql)) {
			prepareBatch(docList, annotation, fieldList, prepareStmt);

			watch.start(stopWatchKey);
			int[] results = prepareStmt.executeBatch();
			watch.stop(stopWatchKey);

			checkBatchResults(results, docList, method);

		} catch (SQLException e) {
			watch.stop(stopWatchKey);

			if (allowedCodes != null && allowedCodes.contains(e.getErrorCode())) return;

			handleSQLException(method, sql, e, LOG_LEVEL_WARN);
		}
	}

	private String buildSqlInsertStatement(Class<?> ref, String tableName, XMLDocument annotation, List<DocumentFields> fieldList) {
		StringBuilder sql = new StringBuilder("INSERT INTO ").append(DBUtills.getTableName(ref, tableName)).append(" (");
		StringBuilder values = new StringBuilder(" VALUES (");

		for (int i = 0; i < fieldList.size(); i++) {
			DocumentFields field = fieldList.get(i);
			sql.append(field.getXMLSqlColumn());
			values.append("?");

			if (i < fieldList.size() - 1) {
				sql.append(", ");
				values.append(", ");
			}
		}

		if (!annotation.sqlXMLDocName().isEmpty()) {
			sql.append(", ").append(annotation.sqlXMLDocName());
			values.append(", ?");
		}

		sql.append(")").append(values).append(")");
		return sql.toString();
	}

	private void prepareBatch(List<? extends DocumentBaseInterface> docList, XMLDocument annotation, List<DocumentFields> fieldList, PreparedStatement prepareStmt) throws SQLException {
		ParameterMetaData paramInfo = prepareStmt.getParameterMetaData();

		for (DocumentBaseInterface document : docList) {
			DocumentBaseInterface clone;
			try {
				clone = document.clone();
			} catch (CloneNotSupportedException e) {
				throw new RuntimeException(e);
			}

			setStatementParameters(document, clone, fieldList, paramInfo, prepareStmt);

			if (!annotation.sqlXMLDocName().isEmpty()) {
				int index = fieldList.size() + 1;
				prepareStmt.setSQLXML(index, new XMLString(clone.toString()).getSQLXML(prepareStmt.getConnection()));
			}

			prepareStmt.addBatch();
		}
	}

	private void setStatementParameters(DocumentBaseInterface document, DocumentBaseInterface clone, List<DocumentFields> fieldList, ParameterMetaData paramInfo, PreparedStatement prepareStmt) throws SQLException {
		for (int i = 0; i < fieldList.size(); i++) {
			DocumentFields node = fieldList.get(i);

			if (node.getXMLSqlColumn() != null && !node.getXMLSqlColumn().isEmpty()) {
				Object value;
				try {
					value = node.getGetter().invoke(document);
				} catch (Exception e) {
					throw new DBDocumentsException(new NGWSMessage(Messages.CLASS_ERROR));
				}

				setPreparedStatementValue(prepareStmt, i + 1, value, paramInfo.getParameterType(i + 1), node);

				if (DocumentFields.FIELDTYPE_CTL.equals(node.getXmlFieldType()) || DocumentFields.FIELDTYPE_ATT.equals(node.getXmlFieldType())) {
					setFieldToNull(clone, node, method);
				}
			}
		}
	}

	private void setPreparedStatementValue(PreparedStatement prepareStmt, int index, Object value, int dbType, DocumentFields node) throws SQLException {
		if (node.getJavaMemeberType().equals(Date.class) && dbType == Types.TIMESTAMP) {
			if (value != null) value = new Timestamp(((Date) value).getTime());
			prepareStmt.setObject(index, value, Types.TIMESTAMP);
		} else if (node.getJavaMemeberType().equals(DateSimple.class) && dbType == Types.TIMESTAMP) {
			if (value != null) value = new Timestamp(((DateSimple) value).getTime());
			prepareStmt.setObject(index, value, Types.TIMESTAMP);
		} else {
			prepareStmt.setObject(index, value, dbType);
		}
	}

	private void setFieldToNull(DocumentBaseInterface clone, DocumentFields node, String method) {
		try {
			Class<?>[] parameterTypes = node.getSetter().getParameterTypes();
			log.trace(method + "Calling setter " + node.getSqlColumn() + " and setting null!");
			node.getSetter().invoke(clone, parameterTypes[0].cast(null));
		} catch (Exception e) {
			log.warn(method + "Error setting null to (" + node.getXmlFieldType() + " field) node: " + node.getFiledName(), e);
		}
	}

	private void checkBatchResults(int[] results, List<? extends DocumentBaseInterface> docList, String method) throws HalServerException {
		boolean failed = false;
		boolean wtf = false;

		int i = 0;
		for (int nUpdated : results) {
			if (nUpdated == Statement.EXECUTE_FAILED) {
				log.warn(method + "Batch insert failed, document :\n" + CryptoBox.encryptToHex(docList.get(i).toString()));
				failed = true;
			} else if (nUpdated != 1) {
				log.warn(method + "Batch insert misuse, document :\n" + CryptoBox.encryptToHex(docList.get(i).toString()));
				wtf = true;
			}
			i++;
		}

		if (failed || wtf) {
			throw new HalServerException(Messages.FATAL_SystemError);
		}
	}

}