package com.tms.controller;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.tms.beans.ExcelCustomStyle;
import com.tms.beans.ExcelReportDev;
import com.tms.beans.GetAllSensor;
import com.tms.beans.GetAllTyre;
import com.tms.beans.MyConstants;
import com.tms.beans.Response;
import com.tms.beans.SensorStatusReport;
import com.tms.beans.TPHistoryDataRequest;
import com.tms.beans.TireStatusReport;
import com.tms.dao.mongo.MongoOperations;
import com.tms.dto.TMSDtoI;
import com.tms.model.TMSMinMaxTempPressure;
import com.tms.model.TMSTireView;
import com.tms.model.TMSUserVehiclesView;
import com.tms.model.UserMaster;
import com.tms.service.MySQLService;

@Controller
@RequestMapping("/api/tms")
public class TMSReportController {

	@Autowired
	private MySQLService mySQLService;

	@Autowired
	private MongoOperations mongoOperations;

	@Autowired
	private TMSDtoI tMSDtoI;

	@RequestMapping(value = "/downloadExcelReport", method = RequestMethod.GET)
	public @ResponseBody Response downloadExcel(HttpServletRequest request, HttpServletResponse response,
			@RequestParam(value = "fileName", required = false) String fileName) {
		Response resp = new Response();
		try {
			int BUFFER_SIZE = 4096;

			// get absolute path of the application
			String filePath = "/tmp/" + request.getSession().getId() + ".xls";
			String reportName = request.getSession().getId();
			if (null != fileName) {
				reportName = fileName.replaceAll(" ", "_");
			}
			File downloadFile = new File(filePath);

			FileInputStream inputStream = new FileInputStream(downloadFile);
			if (null != downloadFile && downloadFile.length() > 0) {
				// set content attributes for the response
				response.setContentType("application/octet-stream");
				response.setContentLength((int) downloadFile.length());

				// set headers for the response
				String headerKey = "Content-Disposition";
				String headerValue = String.format("attachment; filename=\"%s\"", reportName + ".xls");
				response.setHeader(headerKey, headerValue);

				// get output stream of the response
				OutputStream outStream = response.getOutputStream();

				byte[] buffer = new byte[BUFFER_SIZE];
				int bytesRead = -1;

				// write bytes read from the input stream into the output stream
				while ((bytesRead = inputStream.read(buffer)) != -1) {
					outStream.write(buffer, 0, bytesRead);
				}
				// Close
				inputStream.close();
				outStream.close();

				resp.setStatus(true);
				resp.setDisplayMsg(MyConstants.SUCCESS);

				// Delete the file once download
				File removeFile = new File(filePath);
				if (removeFile.exists()) {
					removeFile.delete();
				}
			} else {
				resp.setStatus(false);
				resp.setDisplayMsg(MyConstants.FILE_NOT_FOUND);
			}
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(false);
			resp.setErrorMsg(e.getMessage());
			resp.setDisplayMsg(MyConstants.UNABLE_TO_PROCESS_REQUEST);
		}

		return resp;
	}

	@SuppressWarnings("unchecked")
	@RequestMapping(value = "/getTPReportData", method = RequestMethod.POST)
	public @ResponseBody Response getModifiedVehDetails_POST(HttpServletRequest req,
			@RequestBody TPHistoryDataRequest tpHistoryDataRequest, HttpServletResponse resp) {
		Response response = new Response();
		try {
			HttpSession session = req.getSession(false);
			if (null != session && session.isNew() == false) {
				UserMaster loginUser = (UserMaster) session.getAttribute("LoginUser");
				if (loginUser != null) {
					// Check if any vehicle Ids are selected
					List<Long> vehIds = new ArrayList<>();
					if (null != tpHistoryDataRequest.getVehIds() && tpHistoryDataRequest.getVehIds().size() > 0) {
						// Parse the vehicle ids and add to list
						vehIds = tpHistoryDataRequest.getVehIds();
					} else {
						vehIds = tMSDtoI.getAndSetUserVehToSession(null, mySQLService, session);
					}

					if (tpHistoryDataRequest.isUniqueStatus() && tpHistoryDataRequest.isFileStatus() == false) {
						// Find the latest records with sum count
						response = mongoOperations.getTempPressureDataByDates_Report(vehIds,
								tpHistoryDataRequest.getStartDateTime(), tpHistoryDataRequest.getEndDateTime(), true);
					} else if (tpHistoryDataRequest.isFileStatus()) {
						// Prepare excel and pass the file name in response
						Response unique = mongoOperations.getTempPressureDataByDates_Report(vehIds,
								tpHistoryDataRequest.getStartDateTime(), tpHistoryDataRequest.getEndDateTime(), true);
						// Find all the records
						Response allRecds = mongoOperations.getTempPressureDataByDates_Report(vehIds,
								tpHistoryDataRequest.getStartDateTime(), tpHistoryDataRequest.getEndDateTime(), false);

						List<TMSUserVehiclesView> vehDetails = mySQLService.getVehiclesByVehIds(vehIds, 0);
						// Create Hashmap
						Map<Long, String> vehIdName_map = new HashMap<>();
						for (TMSUserVehiclesView veh : vehDetails) {
							vehIdName_map.put(veh.getVehId(), veh.getVehName());
						}

						TMSMinMaxTempPressure minMaxTempPressureValues = mySQLService
								.getMinMaxTempPressureValues(loginUser.getOrgId(), loginUser.getUserId());

						ExcelReportDev excelReportDev = new ExcelReportDev(minMaxTempPressureValues);
//						response = excelReportDev.prepareTPExcelReport(unique.getResult(), allRecds.getResult(),
//								session.getId(), tpHistoryDataRequest.getStartDateTime(),
//								tpHistoryDataRequest.getEndDateTime(), vehIdName_map);

						response = excelReportDev.prepareTPExcelReport_new(unique.getResult(), allRecds.getResult(),
								session.getId(), tpHistoryDataRequest.getStartDateTime(),
								tpHistoryDataRequest.getEndDateTime(), vehIdName_map);

					} else {

						// Find all the records
						response = mongoOperations.getTempPressureDataByDates_Report(vehIds,
								tpHistoryDataRequest.getStartDateTime(), tpHistoryDataRequest.getEndDateTime(), false);
					}
				} else {
					// Session expired
					response.setStatus(false);
					response.setDisplayMsg(MyConstants.SESSION_EXPIRED);
					response.setErrorMsg(MyConstants.SESSION_EXPIRED);
				}
			} else {
				// Session expired
				response.setStatus(false);
				response.setDisplayMsg(MyConstants.SESSION_EXPIRED);
				response.setErrorMsg(MyConstants.SESSION_EXPIRED);
			}
		} catch (Exception e) {
			e.printStackTrace();
			response.setStatus(false);
			response.setDisplayMsg(MyConstants.UNABLE_TO_PROCESS_REQUEST);
			response.setErrorMsg(e.getMessage());
		}
		return response;
	}

	@SuppressWarnings("unchecked")
	@RequestMapping(value = "/downloadSensorList", method = RequestMethod.GET)
	public @ResponseBody Response downloadAllSensor(HttpServletRequest request, HttpServletResponse response) {
		Response resp = new Response();
		try {
			HttpSession session = request.getSession(false);
			if (null != session && session.isNew() == false) {
				UserMaster loginUser = (UserMaster) session.getAttribute("LoginUser");
				if (loginUser != null) {
					try {
						resp = mySQLService.getAllSensors(loginUser.orgId);
						XSSFWorkbook workbook = new XSSFWorkbook();
						XSSFSheet spreadsheet = workbook.createSheet("SensorStatus");
						XSSFRow row;
						Map<String, Object[]> sensorInfo = new TreeMap<String, Object[]>();
						for (int i = 1; i < resp.getResult().size(); i++) {
							if (i == 1) {
								sensorInfo.put("1", new Object[] { "SENSOR-ID", "STATUS", "TIRE-NUMBER", "TIRE-POSITION",
										"VEHICLE-NUMBER" });
							} else {
								GetAllSensor sensorDetails = (GetAllSensor) resp.getResult().get(i);
								sensorInfo.put(Integer.toString(i),
										new Object[] { sensorDetails.getSensorUID(), sensorDetails.getStatus(),
												sensorDetails.getTireNumber(), sensorDetails.getTyrePosition(),
												sensorDetails.getVehicleNumber() });
							}
						}
						Set<String> keyid = sensorInfo.keySet();
						int rowid = 0;
						for (String key : keyid) {
							row = spreadsheet.createRow(rowid++);
							Object[] objectArr = sensorInfo.get(key);
							int cellid = 0;
							for (Object obj : objectArr) {
								Cell cell = row.createCell(cellid++);
								cell.setCellValue((String) obj);
							}
						}
						FileOutputStream out = new FileOutputStream(new File("/tmp/SensorStatus.xlsx"));
						workbook.write(out);
						out.close();

						//------------------------------------code to download the prepared excel
						int BUFFER_SIZE = 4096;
						String filePath = "/tmp/" + "SensorStatus" + ".xlsx";
						//String reportName = request.getSession().getId();
						String reportName = "TPMS-SensorStatus";
						File downloadFile = new File(filePath);
						FileInputStream inputStream = new FileInputStream(downloadFile);
						if (null != downloadFile && downloadFile.length() > 0) {
							response.setContentType("application/octet-stream");
							response.setContentLength((int) downloadFile.length());
							String headerKey = "Content-Disposition";
							String headerValue = String.format("attachment; filename=\"%s\"", reportName + ".xls");
							response.setHeader(headerKey, headerValue);
							OutputStream outStream = response.getOutputStream();
							byte[] buffer = new byte[BUFFER_SIZE];
							int bytesRead = -1;
							while ((bytesRead = inputStream.read(buffer)) != -1) {
								outStream.write(buffer, 0, bytesRead);
							}
							inputStream.close();
							outStream.close();
							resp.setStatus(true);
							resp.setDisplayMsg(MyConstants.SUCCESS);
							File removeFile = new File(filePath);
							if (removeFile.exists()) {
								removeFile.delete();
							}
						} else {
							resp.setStatus(false);
							resp.setDisplayMsg(MyConstants.FILE_NOT_FOUND);
						}
						resp.setStatus(true);
						resp.setDisplayMsg(MyConstants.SUCCESS);
					} catch (Exception e) {
						e.printStackTrace();
					}

				} else {
					// Session expired
					resp.setStatus(false);
					resp.setDisplayMsg(MyConstants.SESSION_EXPIRED);
					resp.setErrorMsg(MyConstants.SESSION_EXPIRED);
				}
			} else {
				// Session expired
				resp.setStatus(false);
				resp.setDisplayMsg(MyConstants.SESSION_EXPIRED);
				resp.setErrorMsg(MyConstants.SESSION_EXPIRED);
			}
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(false);
			resp.setDisplayMsg(MyConstants.UNABLE_TO_PROCESS_REQUEST);
			resp.setErrorMsg(e.getMessage());
		}

		return resp;
	}

	@SuppressWarnings("unchecked")
	@RequestMapping(value = "/downloadTireList", method = RequestMethod.GET)
	public @ResponseBody Response downloadAllTire(HttpServletRequest request, HttpServletResponse response) {
		Response resp = new Response();
		try {
			HttpSession session = request.getSession(false);
			if (null != session && session.isNew() == false) {
				UserMaster loginUser = (UserMaster) session.getAttribute("LoginUser");
				if (loginUser != null) {
					try {
						resp = mySQLService.getAllTires(loginUser.orgId);
						XSSFWorkbook workbook = new XSSFWorkbook();
						XSSFSheet spreadsheet = workbook.createSheet("TireStatus");
						XSSFRow row;
						Map<String, Object[]> tireInfo = new TreeMap<String, Object[]>();
						for (int i = 1; i < resp.getResult().size(); i++) {
							if (i == 1) {
								tireInfo.put("1", new Object[] { "TIRE-NUMBER", "STATUS", "TIRE-POSITION",
										"VEHICLE-NUMBER" });
							} else {
								GetAllTyre tireDetails = (GetAllTyre) resp.getResult().get(i);
								tireInfo.put(Integer.toString(i),
										new Object[] { tireDetails.getTireNumber(), tireDetails.getStatus(),
												tireDetails.getTyrePosition(), tireDetails.getVehicleNumber() });
							}
						}
						Set<String> keyid = tireInfo.keySet();
						int rowid = 0;
						for (String key : keyid) {
							row = spreadsheet.createRow(rowid++);
							Object[] objectArr = tireInfo.get(key);
							int cellid = 0;
							for (Object obj : objectArr) {
								Cell cell = row.createCell(cellid++);
								cell.setCellValue((String) obj);
							}
						}
						FileOutputStream out = new FileOutputStream(new File("/tmp/TireStatus.xlsx"));
						workbook.write(out);
						out.close();

						//------------------------------------code to download the prepared excel
						int BUFFER_SIZE = 4096;
						String filePath = "/tmp/" + "TireStatus" + ".xlsx";
						//String reportName = request.getSession().getId();
						String reportName = "TPMS-TireStatus";
						File downloadFile = new File(filePath);
						FileInputStream inputStream = new FileInputStream(downloadFile);
						if (null != downloadFile && downloadFile.length() > 0) {
							response.setContentType("application/octet-stream");
							response.setContentLength((int) downloadFile.length());
							String headerKey = "Content-Disposition";
							String headerValue = String.format("attachment; filename=\"%s\"", reportName + ".xls");
							response.setHeader(headerKey, headerValue);
							OutputStream outStream = response.getOutputStream();
							byte[] buffer = new byte[BUFFER_SIZE];
							int bytesRead = -1;
							while ((bytesRead = inputStream.read(buffer)) != -1) {
								outStream.write(buffer, 0, bytesRead);
							}
							inputStream.close();
							outStream.close();
							resp.setStatus(true);
							resp.setDisplayMsg(MyConstants.SUCCESS);
							File removeFile = new File(filePath);
							if (removeFile.exists()) {
								removeFile.delete();
							}
						} else {
							resp.setStatus(false);
							resp.setDisplayMsg(MyConstants.FILE_NOT_FOUND);
						}
						resp.setStatus(true);
						resp.setDisplayMsg(MyConstants.SUCCESS);
						
					} catch (Exception e) {
						e.printStackTrace();
						resp.setStatus(false);
						resp.setErrorMsg(e.getMessage());
						resp.setDisplayMsg(MyConstants.UNABLE_TO_PROCESS_REQUEST);
					}

				} else {
					// Session expired
					resp.setStatus(false);
					resp.setDisplayMsg(MyConstants.SESSION_EXPIRED);
					resp.setErrorMsg(MyConstants.SESSION_EXPIRED);
				}
			} else {
				// Session expired
				resp.setStatus(false);
				resp.setDisplayMsg(MyConstants.SESSION_EXPIRED);
				resp.setErrorMsg(MyConstants.SESSION_EXPIRED);
			}
		} catch (Exception e) {
			e.printStackTrace();
			resp.setStatus(false);
			resp.setErrorMsg(e.getMessage());
			resp.setDisplayMsg(MyConstants.UNABLE_TO_PROCESS_REQUEST);
		}

		return resp;
	}

}
