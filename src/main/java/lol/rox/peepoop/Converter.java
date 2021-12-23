package lol.rox.peepoop;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.jdbi.v3.sqlite3.SQLitePlugin;

import java.io.File;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public class Converter {
    
    private static final Logger LOGGER = LogManager.getLogger(Converter.class);
    
    private final static Field[] schema = {//
            f("LavatoryID", String.class),
            f("Description", String.class),
            f("City", String.class),
            f("Street", String.class),
            f("Number", String.class),
            f("PostalCode", String.class),
            f("Country", String.class),
            f("Longitude", double.class),
            f("Latitude", double.class),
            f("isOwnedByWall", boolean.class),
            f("isHandicappedAccessible", boolean.class),
            f("Price", String.class),
            f("canBePayedWithCoins", boolean.class),
            f("canBePayedInApp", boolean.class),
            f("canBePayedWithNFC", boolean.class),
            f("hasChangingTable", boolean.class),
            f("LabelID", String.class),
            f("hasUrinal", boolean.class)};
    
    private static Field f(String name, Class<?> type) {
        return new Field(name, type);
    }
    
    public static void main(String... args) throws Exception {
    
        OptionParser parser = new OptionParser();
        var xls = parser.accepts("xls")
                .withRequiredArg()
                .required()
                .ofType(String.class)
                .describedAs("path to the xls file to import");
        var sqlite = parser.accepts("sqlite")
                .withRequiredArg()
                .required()
                .ofType(String.class)
                .describedAs("path to the sqlite db to write to");
        OptionSet optionSet = parser.parse(args);
        
        Jdbi jdbi = initDatabase(optionSet.valueOf(sqlite));
        
        LOGGER.info("reading data from {}", optionSet.valueOf(xls));
        Workbook workbook = new XSSFWorkbook(new File(optionSet.valueOf(xls)));
        
        Sheet sheet = workbook.getSheetAt(0);
    
        // the data uses "," as the decimal separator not "."
        NumberFormat germanFormat = NumberFormat.getInstance(Locale.GERMANY);
        DataFormatter formatter = new DataFormatter();
        
        checkHeader(sheet, formatter);
    
        int startRow = 4; // 5th row is the first with content
        
        jdbi.useHandle(handle -> {
            String names = Arrays.stream(schema).map(Field::name).collect(Collectors.joining(","));
            String values = Arrays.stream(schema).map(f -> "?").collect(Collectors.joining(","));
            PreparedBatch preparedBatch = handle.prepareBatch(
                    String.format("insert into toilets (%s) values(%s)", names, values));
            
            for (int i = startRow; i < sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                int cellNr = 0;
                for (int j = 0 ; j < schema.length; j++) {
                    Cell cell = row.getCell(j);
                    Object val = formatter.formatCellValue(cell);
                    if (schema[cellNr].type == boolean.class) {
                        val = germanFormat.parse(val.toString()).intValue() == 1;
                    }
                    if(schema[cellNr].type == double.class) {
                        val = String.valueOf(germanFormat.parse(val.toString()).doubleValue());
                    }
                    
                    preparedBatch.bind(cellNr, val);
                    cellNr++;
                }
                preparedBatch.add();
            }
            int[] counts = preparedBatch.execute();
            LOGGER.info("imported {} records", Arrays.stream(counts).sum());
        });
    }
    
    private static void checkHeader(Sheet sheet, DataFormatter formatter) {
        LOGGER.info("comparing header to known schema");
        Row row = sheet.getRow(3);
        for (int i = 0 ; i < schema.length; i++) {
            String name = formatter.formatCellValue(row.getCell(i));
            if(!schema[i].name.contentEquals(name) ) {
                throw new IllegalStateException(String.format("schema drift detected. %s!=%s", schema[i].name, name));
            }
        }
    }
    
    private static Jdbi initDatabase(String dbPath) {
    
        LOGGER.info("creating database {}", dbPath);
        
        var jdbi = Jdbi.create(String.format("jdbc:sqlite:%s", dbPath)).installPlugin(new SQLitePlugin());
        
        jdbi.useHandle(handle -> handle.execute("drop table if exists toilets"));
        
        StringBuilder builder = new StringBuilder("create table toilets (");
        builder.append(Arrays.stream(schema)
                .map(f -> String.format(" %s %s", f.name, f.type.getSimpleName().toLowerCase(Locale.ROOT)))
                .collect(Collectors.joining(",")));
        builder.append(")");
        
        LOGGER.debug(builder.toString());
        
        jdbi.useHandle(handle -> handle.execute(builder.toString()));
        return jdbi;
    }
    
    record Field(String name, Class<?> type) {}
    
}